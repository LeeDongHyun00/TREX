package com.example.trex_kotlin

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.*
import com.trex.engine.LandmarkFeatures
import com.trex.engine.LandmarkFrame
import com.trex.engine.PoseEvaluationEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V2DeviceAcceptanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun bundledRulesProduceReferenceScoreAndDeliveredKoreanCorrection() {
        val rules=PostureRuleSet.load(context)
        val feedback=V2FormFeedback(rules,"바벨 스쿼트")
        var t=SystemClock.elapsedRealtime()
        feedback.accept(t,mapOf("torso_incl" to 10f,"view_cos" to 1f,"view_sin" to 0f),true)
        var cue:String?=null
        repeat(16) { i -> t+=200;feedback.accept(t,mapOf("torso_incl" to if(i%2==0) 10f else 55f,
            "view_cos" to 1f,"view_sin" to 0f),false)?.let {cue=it} }
        assertEquals(0,V2FormFeedback.score(feedback.current,CoachMode.COACH))
        assertNotNull(cue);assertFalse(cue!!.contains("무릎"));assertFalse(cue!!.contains("척추"))
        assertFalse(V2FormFeedback(rules,"바벨 데드리프트").supported)
        val delivered=java.util.concurrent.CountDownLatch(1)
        lateinit var voice:SpeechCoach
        instrumentation.runOnMainSync { voice=SpeechCoach(context) }
        try {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(!voice.ready && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
            assertTrue("한국어 TTS 준비",voice.ready)
            instrumentation.runOnMainSync { voice.speak("교정 음성 테스트입니다. $cue",onDelivered={delivered.countDown()}) }
            assertTrue("교정 문장의 TTS 완료 콜백",delivered.await(25,java.util.concurrent.TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { voice.shutdown() } }
    }
    private fun nodes():List<AccessibilityNodeInfo> = buildList {
        fun walk(n:AccessibilityNodeInfo?) { if(n==null)return;add(n);for(i in 0 until n.childCount)walk(n.getChild(i)) }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() in setOf(text,text.toDinoCopy()) || it.contentDescription?.toString()==text) }
    private fun await(text:String,timeout:Long=20000) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {if(find(text)!=null)return;Thread.sleep(150)}
        error("찾지 못한 화면: $text · "+nodes().joinToString(" | "){"${it.text} [${it.contentDescription}]"})
    }
    private fun click(text:String) {
        repeat(8) {
            if(find(text)!=null)return@repeat
            nodes().filter{it.isScrollable}.forEach{it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}
            Thread.sleep(150)
        }
        await(text)
        var n=find(text)
        while(n!=null) {if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(500);return};n=n.parent}
        error("누를 수 없는 항목: $text")
    }
    private fun screenshot(name:String) {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null),"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun isolated(workout:Workout, block:()->Unit) {
        check(context.packageName=="com.example.trex_kotlin.v2")
        val prefs=context.getSharedPreferences("trex_store",Context.MODE_PRIVATE);val original=prefs.all.toMap()
        try {
            val store=TrexStore(context);store.guideDone=true;store.loggedIn=true;store.onboarded=true
            store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay();store.savePlan(listOf(workout))
            ActivityScenario.launch(MainActivity::class.java).use {block()}
        } finally {
            val e=prefs.edit().clear();original.forEach { (k,v)->when(v) {
                is String->e.putString(k,v);is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v)
                is Set<*>->{@Suppress("UNCHECKED_CAST") e.putStringSet(k,v as Set<String>)}
            }};e.commit()
        }
    }
    @Test fun timerTargetDoesNotEndUntilUserCompletes() = isolated(
        Workout("v2-test-timer","플랭크","1초 × 1세트","1분",false,"코어",restSeconds=0,target=WorkoutTarget.Duration(1))) {
        click("운동");click("운동 시작");await("세트 완료")
        Thread.sleep(2500);assertNotNull(find("세트 완료"));assertNull(find("완료 세트"))
        screenshot("v2-timer-target");click("세트 완료");await("완료 세트")
        assertTrue(TrexStore(context).loadHistory().orEmpty().flatMap{it.items}.any{it.workoutName=="플랭크"})
    }
    @Test fun cameraSessionCanPauseSwitchResumeAndPersistFeedbackPolicy() = isolated(
        Workout("v2-test-camera","바벨 스쿼트","1회 × 1세트","1분",true,"하체",restSeconds=0,target=WorkoutTarget.Repetitions(1))) {
        val folder=File(context.noBackupFilesDir,"pose_v2")
        val before=folder.listFiles().orEmpty().map{it.name}.toSet()
        click("운동");click("운동 시작");await("준비 건너뛰기");click("준비 건너뛰기")
        await("trex_v2 · 자세 교정");Thread.sleep(4500)
        click("일시정지");click("카메라 전환");click("재개");Thread.sleep(3000)
        screenshot("v2-camera-observation");click("세트 완료");await("완료 세트")
        val deadline=SystemClock.elapsedRealtime()+5000
        var file:File?=null
        while(file==null && SystemClock.elapsedRealtime()<deadline) {
            file=folder.listFiles().orEmpty().firstOrNull{it.name !in before && it.name.endsWith("summary.json")};Thread.sleep(100)
        }
        val result=JSONObject(requireNotNull(file).readText())
        assertEquals("trex-observation/2.0.0",result.getString("engine"));assertTrue(result.getBoolean("corrective_voice"))
        assertEquals(V2FormFeedback.POLICY_VERSION,result.getString("feedback_policy"))
        assertTrue(result.isNull("reference_score"))
        assertFalse(result.getBoolean("baseline_applied"));assertEquals("UNJUDGED",result.getString("form_verdict"))
        assertTrue(result.getInt("frames")>0)
        assertTrue(folder.listFiles().orEmpty().none{it.name !in before && it.name.endsWith("frames.jsonl")})
    }
    @Test fun sharedEngineAndRealPoseModelRunOnDeviceWithoutAccuracyClaim() {
        val profile=com.trex.engine.ExerciseRepProfiles.forExercise("바벨 스쿼트")!!
        val engine=PoseEvaluationEngine(profile);var t=0L
        fun frame(v:Float)=engine.process(t,mapOf("knee_mean" to v,"view_cos" to 1f,"view_sin" to 0f)).also{t+=200}
        repeat(20){frame(175f)}
        val result=listOf(160f,140f,90f,90f,120f,150f,165f,175f).map(::frame).last()
        assertEquals(1,result.counts.total)
        val files=File(context.getExternalFilesDir(null),"engine_probe").listFiles().orEmpty().filter{it.extension=="jpg"}.sortedBy{it.name}
        assertEquals(3,files.size)
        val analyzer=PostureAnalyzer(context,PoseModel.FULL,preferGpu=true)
        val timings=mutableListOf<Long>();var visible=0
        try {
            assertTrue(analyzer.ensureReady())
            val extractor=LandmarkFeatures()
            for(file in files) {
                val raw=BitmapFactory.decodeFile(file.absolutePath)
                val ratio=640f/maxOf(raw.width,raw.height)
                val bitmap=android.graphics.Bitmap.createScaledBitmap(raw,(raw.width*ratio).toInt(),(raw.height*ratio).toInt(),true)
                repeat(5) {
                    val sample=analyzer.analyzeBitmap(bitmap,SystemClock.elapsedRealtime())
                    val f=extractor.extract(LandmarkFrame(sample.normalizedXy,sample.rawWorld,sample.visibility,sample.imageWidth,sample.imageHeight),false)
                    if(f.isNotEmpty())visible++
                    timings+=sample.inferMs;Thread.sleep(5)
                }
                if(bitmap!==raw)bitmap.recycle();raw.recycle()
            }
            assertTrue(visible>0)
            File(context.getExternalFilesDir(null),"v2-device-results.json").writeText(JSONObject()
                .put("engine",com.trex.engine.ENGINE_VERSION).put("device",android.os.Build.MODEL).put("delegate",analyzer.stats().delegate)
                .put("frames",timings.size).put("feature_frames",visible).put("infer_ms",org.json.JSONArray(timings))
                .put("synthetic_reps",result.counts.total).put("human_accuracy_validated",false).toString(2))
        } finally {analyzer.close()}
    }
}
