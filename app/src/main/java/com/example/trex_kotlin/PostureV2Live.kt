package com.example.trex_kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.*
import com.trex.engine.EngineOutput
import com.trex.engine.ExerciseCatalog
import com.trex.engine.LabEngine
import com.trex.engine.LandmarkFeatures
import com.trex.engine.LandmarkFrame
import com.trex.engine.MovementContracts
import com.trex.engine.RangeGoal
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private data class PreparedCapture(val key: String, val at: Long, val rotation: Int, val front: Boolean,
    val floorConfirmed: Boolean, val diagnostic: Boolean)
private object CaptureTransfer { var pending: PreparedCapture? = null }

/** 기존 세션 화면의 진입점. 운동 경계는 카메라·추론·마감 콜백까지 함께 나눈다. */
@Composable
fun PostureLiveSessionScreen(
    workout: Workout, index: Int, total: Int, timeLeft: Int, totalSeconds: Int, paused: Boolean,
    onTogglePause: () -> Unit, onNext: () -> Unit, onExit: () -> Unit,
    onSetReport: (PostureSetReport) -> Unit = {}, onFallbackToTimer: () -> Unit = {}, speech: SpeechCoach,
    setLabel: String = "1 / 1 세트", onSkip: () -> Unit = onNext, registerFinalizer: ((() -> Unit)?) -> Unit = {},
    repetitions: Int = 0, onRepetitions: (Int) -> Unit = {}, onRepDetected: () -> Unit = {},
    onPartial: () -> Unit = onSkip, preparing: Boolean = false, onPrepared: () -> Unit = {},
) = key(workout.id) {
    PostureV2Content(workout, timeLeft, totalSeconds, paused, onTogglePause, onNext, onExit, onSetReport,
        onFallbackToTimer, speech, setLabel, onSkip, registerFinalizer, repetitions, onRepetitions, preparing, onPrepared)
}

@Composable
private fun PostureV2Content(
    workout: Workout, timeLeft: Int, totalSeconds: Int, paused: Boolean, onTogglePause: () -> Unit,
    onNext: () -> Unit, onExit: () -> Unit, onSetReport: (PostureSetReport) -> Unit,
    onFallback: () -> Unit, speech: SpeechCoach, setLabel: String, onSkip: () -> Unit,
    registerFinalizer: ((() -> Unit)?) -> Unit, repetitions: Int, onRepetitions: (Int) -> Unit,
    preparing: Boolean, onPrepared: () -> Unit,
) {
    KeepScreenOn()
    val c = Trex.c
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val rotation = rememberTrexDisplayRotation()
    val transferred = remember { CaptureTransfer.pending?.takeIf {
        !preparing && it.key == workout.id.substringBefore("::set:") && it.rotation == rotation && SystemClock.elapsedRealtime()-it.at in 0..30000
    }.also { if(!preparing) CaptureTransfer.pending=null } }
    val base = remember { com.trex.engine.ExerciseRepProfiles.forExercise(workout.name)!! }
    val uiProfile = remember { ExerciseProfiles.forName(workout.name)!! }
    val pattern = remember { com.trex.engine.RepMovementPattern.valueOf(workout.resolvedRepPattern()?.name ?: base.defaultPattern.name) }
    val prefs = remember { context.getSharedPreferences("v2_observation_options", 0) }
    var rangeText by remember { mutableStateOf(prefs.getString("range:${base.exercise}", "").orEmpty()) }
    var fromFloor by remember { mutableStateOf(prefs.getBoolean("floor:${base.exercise}", false)) }
    var floorConfirmed by remember { mutableStateOf(transferred?.floorConfirmed == true) }
    var diagnostic by remember { mutableStateOf(transferred?.diagnostic == true) }
    var settings by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().padding(24.dp), verticalArrangement=Arrangement.Center) {
            Text("카메라 권한이 필요해요", color=c.text, fontSize=24.sp)
            Text("운동은 직접 기록으로 계속할 수 있어요.", color=c.text2)
            Cta("카메라 허용", { permission.launch(Manifest.permission.CAMERA) })
            GhostButton("직접 기록으로 계속", onFallback)
            GhostButton("나가기", onExit)
        }
        return
    }

    // 설정 취소는 입력 전 값으로 돌아간다. 준비 화면의 임시 입력을 다음 세트의 설정으로 오인하지 않는다.
    var settingsBefore by remember { mutableStateOf(listOf(rangeText,fromFloor.toString(),floorConfirmed.toString(),diagnostic.toString())) }
    LaunchedEffect(settings) { if(settings) settingsBefore=listOf(rangeText,fromFloor.toString(),floorConfirmed.toString(),diagnostic.toString()) }
    fun cancelSettings() {
        rangeText=settingsBefore[0];fromFloor=settingsBefore[1].toBoolean()
        floorConfirmed=settingsBefore[2].toBoolean();diagnostic=settingsBefore[3].toBoolean()
        settings=false;pendingStart=false
    }
    val profile = remember(fromFloor) { MovementContracts.deadliftStart(base, fromFloor) }
    val goal = remember(rangeText) { rangeText.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }?.let(::RangeGoal) }
    val engine = remember(profile, goal) { LabEngine(profile, pattern, goal) }
    val extractor = remember(engine) { LandmarkFeatures() }
    val boundary = remember { ObservationEpoch() }
    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu=true) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val closing = remember { AtomicBoolean(false) }
    val finalized = remember { AtomicBoolean(false) }
    val store = remember { V2SessionStore(context) }
    val setId = remember { UUID.randomUUID().toString() }
    val startedAt = remember { longArrayOf(0L) }
    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var sampleAt by remember { mutableStateOf(0L) }
    var output by remember { mutableStateOf<EngineOutput?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var useFront by remember { mutableStateOf(transferred?.front ?: true) }
    var muted by remember { mutableStateOf(speech.muted) }
    var manualActual by remember { mutableStateOf<Int?>(null) }
    val actualEdited = manualActual != null
    var mode by remember { mutableStateOf(ModeStore(context).get(workout.name)) }
    val liveEngine = rememberUpdatedState(engine)
    val liveExtractor = rememberUpdatedState(extractor)
    val running = rememberUpdatedState(!paused && !preparing && !settings)
    val preparingNow = rememberUpdatedState(preparing)
    val floorNow = rememberUpdatedState(floorConfirmed)
    val diagnosticNow = rememberUpdatedState(diagnostic)
    val actualNow = rememberUpdatedState(repetitions)
    val onReportNow = rememberUpdatedState(onSetReport)
    val countCallback = rememberUpdatedState(onRepetitions)
    val modeNow = rememberUpdatedState(mode)
    val latestOutput = remember { arrayOfNulls<EngineOutput>(1) }
    val previewView = remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FIT_CENTER; implementationMode=PreviewView.ImplementationMode.COMPATIBLE } }
    val thermal = remember { ThermalMonitor(context) }
    val policy = remember { InferencePolicy(sampleIntervalMs=200L) }
    val lastInfer = remember { longArrayOf(0L) }
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    DisposableEffect(Unit) {
        thermal.start(main) {}
        onDispose { closing.set(true); boundary.invalidate(); analysisRef[0]?.clearAnalyzer(); thermal.stop(); executor.execute { analyzer.close() }; executor.shutdown() }
    }
    LaunchedEffect(muted) { speech.muted=muted; if(muted) speech.stop() }
    LaunchedEffect(paused, preparing, settings, useFront, rotation, engine) {
        boundary.invalidate { engine.interrupt(); extractor.reset() }
        sample=PoseSample.empty(); sampleAt=0
        output=output?.copy(measurements=emptyMap(), phase="UNOBSERVABLE", status="촬영 위치를 다시 확인합니다")
        if (!preparing && startedAt[0] == 0L) startedAt[0]=SystemClock.elapsedRealtime()
        if (paused || settings) speech.stop()
    }
    // 입력이 멎었을 때 마지막 숫자/평가를 현재 관측처럼 보여 주지 않는다.
    LaunchedEffect(engine) {
        while(true) {
            delay(250)
            if(sampleAt>0 && SystemClock.elapsedRealtime()-sampleAt>1000) {
                boundary.invalidate { engine.interrupt(); extractor.reset() }
                sample=PoseSample.empty(); sampleAt=0
                output=output?.copy(measurements=emptyMap(), phase="UNOBSERVABLE", status="영상이 늦어 관측을 잠시 멈췄어요")
            }
        }
    }
    fun finish() = boundary.exclusive {
        if(preparingNow.value || !finalized.compareAndSet(false,true)) return@exclusive
        val result=latestOutput[0]
        val count=result?.counts
        val lines=buildList {
            if(!profile.isometric) add("자동 관측 ${count?.total ?: 0}회" + if(actualEdited) " · 직접 입력 ${manualActual}회" else " · 직접 입력 없음")
            result?.rangeMet?.let { add("직접 선택한 왕복 범위 ${goal?.amplitude} 충족 ${it}회 · 정자세 판정 아님") }
            if(profile.isometric) add("경과 ${(SystemClock.elapsedRealtime()-startedAt[0])/1000}초 · 관측 유지 ${(result?.observedHoldMs ?: 0)/1000}초 · 정렬 평가 시간 없음")
            add("관측 프레임 ${result?.acceptedFrames ?: 0}/${result?.totalFrames ?: 0}")
            result?.comparison?.let(::add)
            com.trex.engine.FormMetrics.forExercise(profile).forEach { metric ->
                result?.measurements?.get(metric.key)?.let { value ->
                    add("마지막 관측 · ${metric.label} ${String.format(java.util.Locale.KOREA,"%.2f",value)}")
                }
            }
            add(profile.limitations)
            add("정자세 점수와 자동 교정 음성은 제공하지 않습니다")
        }
        store.summary(setId,profile.exercise,pattern.name,fromFloor,goal?.amplitude,manualActual ?: actualNow.value,actualEdited,result,profile.toString(),
            (SystemClock.elapsedRealtime()-startedAt[0]).coerceAtLeast(0))
        onReportNow.value(PostureSetReport(setId,profile.exercise,"${workout.name} · $setLabel",modeNow.value,
            result?.totalFrames ?: 0,false,emptyList(),null,null,null,lines,
            count?.let { RepObservationSummary(pattern.name,it.total,it.left,it.right,it.both,it.unknown) },
            observationEngine=true,userEnteredReps=manualActual,rangeGoalReps=result?.rangeMet))
    }
    val finishNow = rememberUpdatedState<() -> Unit> { finish() }
    DisposableEffect(preparing) {
        if (!preparing) registerFinalizer { finishNow.value() }
        onDispose { if(!preparing) { finishNow.value(); registerFinalizer(null) } }
    }
    var spokenCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(output?.counts?.total, output?.rangeMet, manualActual) {
        if(!preparing) countCallback.value(com.trex.engine.RepProgress(output?.counts?.total ?: 0,
            output?.rangeMet ?: goal?.let { 0 },manualActual).goalProgress)
        val result=output ?: return@LaunchedEffect
        if(result.counts.total>spokenCount) {
            spokenCount=result.counts.total
            if(result.events.any { goal?.met(it) != false } && !paused && !speech.muted) speech.speak("${result.rangeMet ?: result.counts.total}", flush=false)
        }
    }
    LaunchedEffect(useFront, rotation) {
        val provider = runCatching { suspendCoroutine<ProcessCameraProvider> { continuation ->
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({ try { continuation.resume(future.get()) } catch(e:Exception) { continuation.resumeWithException(e) } },main)
        } }.getOrElse { cameraError="카메라를 준비하지 못했어요. 직접 기록으로 계속할 수 있어요."; return@LaunchedEffect }
        val selector=ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
        val preview=Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider=previewView.surfaceProvider }
        val analysis=ImageAnalysis.Builder().setTargetRotation(rotation).setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysisRef[0]=analysis
        analysis.setAnalyzer(executor) { image ->
            val ticket=boundary.ticket(); val now=SystemClock.elapsedRealtime()
            if(closing.get() || !policy.shouldInfer(now,lastInfer[0],if(running.value) InferencePhase.RECORDING else InferencePhase.IDLE,thermal.status)) {
                image.close(); return@setAnalyzer
            }
            lastInfer[0]=now
            try {
                val wasRunning=running.value
                val observed=analyzer.analyze(image,now)
                boundary.applyIfCurrent(ticket) {
                    if(closing.get() || finalized.get()) return@applyIfCurrent
                    val f=liveExtractor.value.extract(LandmarkFrame(observed.normalizedXy,observed.rawWorld,observed.visibility,
                        observed.imageWidth,observed.imageHeight),profile.floor)
                    val fresh=SystemClock.elapsedRealtime()-now<=1000
                    val result=if(wasRunning && running.value) liveEngine.value.process(now,f,fresh && f.isNotEmpty(),floorNow.value) else null
                    if(result!=null) {
                        latestOutput[0]=result
                        if(diagnosticNow.value) store.frame(setId,observed,f,result)
                    }
                    main.execute {
                        boundary.applyIfCurrent(ticket) {
                            if(!closing.get()) {
                                sample=if(fresh) observed else PoseSample.empty(); sampleAt=now
                                if(result!=null) output=result
                                cameraError=analyzer.stats().error
                            }
                        }
                    }
                }
            } catch(e:Exception) {
                boundary.applyIfCurrent(ticket) { liveEngine.value.interrupt(); liveExtractor.value.reset() }
                main.execute { boundary.applyIfCurrent(ticket) { cameraError="몸을 인식하지 못했어요. 직접 기록으로 계속할 수 있어요." } }
            } finally { image.close() }
        }
        try {
            provider.bindToLifecycle(lifecycle,if(useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
            cameraError=null
            awaitCancellation()
        } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
        catch(e:Exception) { cameraError="카메라를 열 수 없어요. 직접 기록으로 계속할 수 있어요." }
        finally { analysis.clearAnalyzer(); provider.unbind(preview,analysis) }
    }

    fun prepared() {
        CaptureTransfer.pending=PreparedCapture(workout.id.substringBefore("::set:"),SystemClock.elapsedRealtime(),rotation,useFront,floorConfirmed,diagnostic)
        onPrepared()
    }
    fun start() { if(profile.floor && !floorConfirmed) { pendingStart=true; settings=true } else prepared() }
    val message = when {
        paused -> "일시정지"
        cameraError!=null -> cameraError!!
        sampleAt==0L -> "몸 전체를 화면에 담아 주세요"
        output?.phase=="UNOBSERVABLE" -> output!!.status
        profile.isometric -> "관측 유지 ${(output?.observedHoldMs ?: 0)/1000}초 · 자세 판정 없음"
        else -> output?.status ?: "시작 위치에서 잠시 멈춰 주세요"
    }
    PostureAdaptiveLayout(preparing=preparing, immersive=!preparing, panelProgress=1f,
        header={ if(!preparing) LiveWorkoutHud(workout,repetitions,timeLeft,totalSeconds,setLabel,paused,compact=false,message=null,
            evaluationEngineLabel="trex_v2 · 동작 관측",repDetail=output?.let {
                if(profile.isometric) "관측 유지 ${it.observedHoldMs/1000}초 · 정렬 미평가"
                else "카메라 ${it.counts.total}회" + (it.rangeMet?.let { n -> " · 선택 범위 ${n}회" } ?: "") }) },
        camera={
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory={previewView},modifier=Modifier.fillMaxSize())
                LivePoseOverlay(sample,useFront,c.lime,modifier=Modifier.fillMaxSize())
            }
        },
        controls={
            if(preparing) CapturePreparationPanel(uiProfile,sample,sampleAt,paused || settings,useFront,muted,
                onMute={muted=!muted},onCamera={floorConfirmed=false;useFront=!useFront},speech=speech,onStart={start()},onExit=onExit,
                modifier=Modifier.fillMaxSize().background(c.bg).padding(12.dp),cameraError=cameraError,onFallback=onFallback,
                repCountHint=profile.countDefinition,evaluationEngineLabel="trex_v2 · 26종 동작 관측",
                modeControl={ TextButton({settings=true}) { Text("관측 설정 · 범위 / 출발 / 진단",color=c.primaryText) } })
            else Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(message,color=c.text,fontSize=15.sp)
                output?.comparison?.takeIf { output?.phase != "UNOBSERVABLE" }?.let { Text(it,color=c.text2,fontSize=12.sp) }
                Text("관측 횟수는 참고값이에요. 목표 도달 후 직접 세트를 완료해 주세요.",color=c.text2,fontSize=11.sp)
                if(goal!=null) Text("선택한 범위를 충족한 반복으로 목표를 셉니다",color=c.text2,fontSize=11.sp)
                manualActual?.let { Text("직접 입력 ${it}회 · 카메라 관측은 별도 보존",color=c.text2,fontSize=11.sp) }
                if(profile.floor && !floorConfirmed) TextButton({settings=true}) { Text("촬영 방향 확인",color=c.primaryText) }
                if(cameraError!=null) TextButton(onFallback) { Text("직접 기록으로 계속",color=c.primaryText) }
                WorkoutSessionActions(workout,manualActual ?: repetitions,!profile.isometric,paused,onTogglePause,
                    { manualActual=it },onNext,onSkip,{finish();onExit()},directTools={
                        SessionTool("음성",if(muted) "음성 안내 켜기" else "음성 안내 끄기",
                            if(muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,{muted=!muted},Modifier.weight(1f))
                        SessionTool("카메라","카메라 전환",Icons.Rounded.Cameraswitch,{floorConfirmed=false;useFront=!useFront},Modifier.weight(1f))
                    },onComplete={finish();onNext()})
            }
        })
    if(settings) AlertDialog(onDismissRequest={cancelSettings()},
        title={Text("관측 설정")},
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(profile.limitations,fontSize=13.sp)
            if(preparing) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode==CoachMode.COACH,{mode=CoachMode.COACH;ModeStore(context).set(workout.name,mode)},label={Text("동작 안내")})
                    FilterChip(mode==CoachMode.TRACK,{mode=CoachMode.TRACK;ModeStore(context).set(workout.name,mode)},label={Text("기록")})
                }
                Text("두 모드 모두 같은 움직임을 측정하며 자세 정답을 판정하지 않습니다.",fontSize=12.sp)
            }
            if(preparing && !profile.isometric) {
                val angle = (profile.commonSignal ?: profile.leftSignal)?.minAmp?.let { it>=10 } == true
                OutlinedTextField(rangeText,{rangeText=it},label={Text("선택 범위 (${if(angle) "각도 변화 °" else "정규화 변화량"})")},
                    supportingText={Text("비우면 관측 반복만 셉니다. 올바른 자세의 기준이 아닙니다.")},singleLine=true)
            }
            if(preparing && profile.exercise=="바벨 데드리프트") Row {
                FilterChip(!fromFloor,{fromFloor=false},label={Text("선 자세 출발")})
                FilterChip(fromFloor,{fromFloor=true},label={Text("몸을 숙여 출발")})
            }
            if(profile.floor) Row { Checkbox(floorConfirmed,{floorConfirmed=it});Text("${uiProfile.capture.title}에 휴대폰을 고정했어요") }
            if(preparing) Row { Checkbox(diagnostic,{diagnostic=it});Text("이 세트 진단 좌표 저장 · 기기 안에 7일, 영상 저장 없음") }
            Text("바벨·척추·통증을 판정하지 않으며 저장 기준선을 정답으로 자동 적용하지 않습니다.",fontSize=12.sp)
        }},
        confirmButton={ TextButton(onClick={
            prefs.edit().putString("range:${base.exercise}",rangeText).putBoolean("floor:${base.exercise}",fromFloor).apply()
            settings=false
            if(pendingStart) {pendingStart=false;prepared()}
        },enabled=(!profile.floor || floorConfirmed) && (rangeText.isBlank() || goal!=null)) { Text(if(pendingStart) "확인하고 시작" else "적용") } },
        dismissButton={TextButton({cancelSettings()}) {Text("닫기")}})
}
