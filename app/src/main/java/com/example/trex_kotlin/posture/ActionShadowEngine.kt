package com.example.trex_kotlin.posture

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 시험 실행 전용. 고속 카운터의 스레드를 막지 않고 한 작업 이상 쌓지 않는다. */
class ActionShadowEngine(context: Context) : AutoCloseable {
    private val assets=context.applicationContext.assets
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val generation=AtomicInteger(0)
    private val capture=AtomicBoolean(false)
    private val skipped=AtomicInteger(0)
    private val traceLock=Any()
    private val observations=ArrayList<ActionAssessment>()
    private val rawFrames=ArrayList<PosePacketV2>()
    private var observationsDropped=0
    private var rawDropped=0
    private var interpreter: Interpreter?=null
    private var calibration: ActionCalibration?=null
    private var loadAttempted=false
    private var loadError: String?=null
    private var bundleId: String?=null
    private var modelHash: String?=null
    private var workerGeneration=-1
    private val window=ActionWindow()
    private val flat=FloatArray(ActionInputBuilder.FRAMES*ActionInputBuilder.FEATURES)
    private val input=ByteBuffer.allocateDirect(flat.size*4).order(ByteOrder.nativeOrder())
    private val output=Array(1) { FloatArray(27) }
    private var lastInferMs: Long?=null
    @Volatile var lastState=ActionState.WARMUP; private set

    fun setCaptureEnabled(enabled: Boolean) {
        capture.set(enabled)
        if(!enabled) synchronized(traceLock) { rawFrames.clear(); rawDropped=0 }
    }

    /** 새 세트: 이전 작업 결과는 세대 비교로 폐기한다. */
    fun resetSession() {
        generation.incrementAndGet(); skipped.set(0); lastState=ActionState.WARMUP
        synchronized(traceLock) { observations.clear(); rawFrames.clear(); observationsDropped=0; rawDropped=0 }
    }

    /** 일시정지/카메라 변경: 로그는 유지하되 부분 입력을 끊는다. */
    fun breakContinuity() { generation.incrementAndGet(); lastState=ActionState.WARMUP }

    fun offer(packet: PosePacketV2, selected: String, thermalStatus: Int) {
        if(closed.get()) return
        if(!busy.compareAndSet(false,true)) { skipped.incrementAndGet(); return }
        val acceptedGeneration=generation.get()
        try {
            worker.execute {
                try {
                    if(closed.get() || acceptedGeneration != generation.get()) return@execute
                    if(workerGeneration != acceptedGeneration) {
                        window.reset(); lastInferMs=null; workerGeneration=acceptedGeneration
                    }
                    if(capture.get()) synchronized(traceLock) {
                        if(capture.get() && acceptedGeneration == generation.get()) {
                            if(rawFrames.size < RAW_LIMIT) rawFrames += packet else rawDropped++
                        }
                    }
                    val t=packet.captureMs
                    val encoded=ActionInputBuilder.encode(packet)
                    val observable=window.add(t,packet.epoch,encoded)
                    if(lastInferMs?.let { t > it && t-it < INTERVAL_MS } == true) return@execute
                    lastInferMs=t
                    val result=when {
                        !observable -> ActionAssessment(t,packet.epoch,ActionState.UNOBSERVABLE)
                        thermalStatus >= InferencePolicy.THERMAL_MODERATE -> {
                            window.reset()
                            ActionAssessment(t,packet.epoch,ActionState.UNAVAILABLE,reason="thermal_budget")
                        }
                        window.size < 4 -> ActionAssessment(t,packet.epoch,ActionState.WARMUP)
                        else -> classify(packet,selected)
                    }
                    synchronized(traceLock) {
                        if(!closed.get() && acceptedGeneration == generation.get()) {
                            lastState=result.state
                            if(observations.size < OBSERVATION_LIMIT) observations += result else observationsDropped++
                        }
                    }
                } catch(error: Exception) {
                    recordFailure(packet,acceptedGeneration,error.javaClass.simpleName)
                } catch(error: LinkageError) {
                    recordFailure(packet,acceptedGeneration,error.javaClass.simpleName)
                } finally { busy.set(false) }
            }
        } catch(_: RejectedExecutionException) { busy.set(false) }
    }

    private fun recordFailure(packet: PosePacketV2, expected: Int, reason: String) {
        // 오류 메시지에는 경로/개인 값이 들어갈 수 있어 제한된 타입 이름만 기록한다.
        loadError=reason; window.reset()
        synchronized(traceLock) {
            if(expected == generation.get() && !closed.get()) {
                lastState=ActionState.UNAVAILABLE
                if(observations.size < OBSERVATION_LIMIT)
                    observations += ActionAssessment(packet.captureMs,packet.epoch,ActionState.UNAVAILABLE,reason=reason)
                else observationsDropped++
            }
        }
    }

    private fun load() {
        if(loadAttempted) return
        loadAttempted=true
        val manifest=assets.open("posture/action/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getString("schema") == "trex.action.bundle/1")
        require(manifest.getString("mode") == "shadow")
        require(!manifest.getBoolean("can_affect_count") && !manifest.getBoolean("can_speak"))
        require(manifest.getString("input_schema") == ActionInputBuilder.SCHEMA)
        require(manifest.getInt("frames") == ActionInputBuilder.FRAMES && manifest.getInt("features") == ActionInputBuilder.FEATURES)
        require(manifest.getString("pose_model") == "full")
        val names=manifest.getJSONArray("labels")
        val labels=List(names.length()) { names.getString(it) }
        require(labels.dropLast(1).toSet() == ExerciseProfiles.all.map { it.referenceExercise }.toSet())
        require(labels.last() == "__other_exercise__")
        val modelName=manifest.getString("model_file")
        require(modelName == "exercise_context.tflite")
        val bytes=assets.open("posture/action/$modelName").use { stream -> stream.readBytes() }
        require(bytes.size in 1..(2*1024*1024))
        fun hash(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash(bytes) == manifest.getString("sha256"))
        val poseHash=assets.open("posture/pose_landmarker_full.task").use { hash(it.readBytes()) }
        require(poseHash == manifest.getString("pose_sha256"))
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        val engine=Interpreter(model,Interpreter.Options().setNumThreads(1))
        try {
            require(engine.getInputTensor(0).shape().contentEquals(intArrayOf(1,16,132)))
            require(engine.getOutputTensor(0).shape().contentEquals(intArrayOf(1,27)))
            require(engine.getInputTensor(0).dataType()==DataType.FLOAT32 && engine.getOutputTensor(0).dataType()==DataType.FLOAT32)
            calibration=ActionCalibration(labels,manifest.getDouble("temperature").toFloat(),
                manifest.getDouble("confidence_threshold").toFloat(),manifest.getDouble("energy_threshold").toFloat())
            interpreter=engine
            synchronized(traceLock) { bundleId=manifest.getString("bundle_id"); modelHash=manifest.getString("sha256") }
        } catch(error: Exception) { engine.close(); throw error }
    }

    private fun classify(packet: PosePacketV2, selected: String): ActionAssessment {
        load()
        if(loadError != null || interpreter == null || calibration == null)
            return ActionAssessment(packet.captureMs,packet.epoch,ActionState.UNAVAILABLE,reason=loadError ?: "no_model")
        val started=System.nanoTime()
        window.copyInto(flat); input.rewind(); input.asFloatBuffer().put(flat)
        interpreter!!.run(input,output)
        val ms=(System.nanoTime()-started)/1_000_000f
        return calibration!!.assess(packet.captureMs,packet.epoch,output[0],selected,ms)
    }

    fun snapshot(baseMs: Long): ActionSessionLog = synchronized(traceLock) {
        ActionSessionLog(bundleId,modelHash,observations.map { it.copy(tMs=it.tMs-baseMs) },
            if(capture.get()) rawFrames.map { it.copy(captureMs=it.captureMs-baseMs) } else emptyList(),
            observationsDropped,rawDropped,skipped.get(),capture.get())
    }

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        generation.incrementAndGet()
        // 세트 마감 onDispose가 뒤따라도 스냅샷을 보존한다. 원자료는 객체 해제 때 함께 해제된다.
        worker.execute { interpreter?.close(); interpreter=null }
        worker.shutdown()
    }
    companion object { const val INTERVAL_MS=200L; const val RAW_LIMIT=600; const val OBSERVATION_LIMIT=1200 }
}
