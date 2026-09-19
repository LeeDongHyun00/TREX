package com.trex.engine.lab

import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trex.engine.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class LabDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun installedAppUsesOnlyTheIndependentEngineAndDisabledResearchCatalog() {
        assertEquals("com.trex.engine.lab",context.packageName)
        assertEquals(26,ExerciseRepProfiles.all.size)
        for(name in listOf("LiveCoach","RepCounter","PostureRuleSet","PostureAnalyzer")) {
            try { Class.forName("com.example.trex_kotlin.posture.$name"); fail("기존 엔진이 포함됨: $name") } catch(_: ClassNotFoundException) { }
        }
        val json=JSONObject(context.assets.open("research-catalog.json").bufferedReader().use{it.readText()})
        assertEquals(147,json.getJSONArray("items").length())
        repeat(147){ assertFalse(json.getJSONArray("items").getJSONObject(it).getBoolean("automatic_coaching_enabled")) }
        assertEquals(64,BuildConfig.ENGINE_SHA256.length)
    }
    @Test fun realMediaPipeImageInputProducesRawJointsAndIndependentFeatures() {
        val source=File(context.getExternalFilesDir(null),"probe.jpg")
        assertTrue("ADB로 준비한 실제 이미지가 필요합니다",source.exists())
        val bitmap=BitmapFactory.decodeFile(source.path)
        val camera=PoseCamera(context)
        try {
            val result=camera.detectBitmap(bitmap,0)
            assertNull(result.error); assertNotNull(result.frame)
            assertEquals(99,result.frame!!.world.size)
            assertTrue(result.frame!!.visibility.count{it >= .5f}>15)
            val features=LandmarkFeatures().extract(result.frame!!,false)
            assertTrue(features.containsKey("knee_L")); assertTrue(features.containsKey("view_cos"))
            File(context.getExternalFilesDir(null),"model-check.json").writeText(JSONObject().put("delegate",result.delegate)
                .put("infer_ms_including_initialization",result.inferMs).put("features",features.size)
                .put("engine_sha256",BuildConfig.ENGINE_SHA256).toString())
        } finally { camera.close(); bitmap.recycle() }
    }
    @Test fun logRoundTripReplaysCountsAndExportsReadableZipWithoutInventedTruth() {
        // 사용자 실험 목록에 합성 검사를 넣지 않는다.
        val isolated=object:ContextWrapper(context) {
            override fun getFilesDir()=File(context.cacheDir,"instrumentation-files").apply{mkdirs()}
        }
        val profile=ExerciseRepProfiles.forExercise("바벨 스쿼트")!!
        val writer=SessionLog(isolated,profile,profile.defaultPattern,false)
        val engine=LabEngine(profile)
        var t=0L
        var latest:EngineOutput?=null
        fun frame(value:Float) {
            val f=mapOf("view_cos" to 1f,"view_sin" to 0f,"knee_mean" to value)
            latest=engine.process(t,f)
            writer.frame(CameraResult(null,0,"SYNTHETIC_TEST"),f,latest!!,true); t+=300
        }
        repeat(20){frame(170f)}
        repeat(5){ for(v in listOf(150f,120f,100f,120f,150f,170f))frame(v) }
        writer.finish("instrumentation_synthetic",latest)
        assertEquals(5,latest!!.counts.total)
        SessionLog.truth(writer.file,null,null,null,"synthetic_test","실제 사람 횟수 아님")
        val rows=writer.file.readLines().map(::JSONObject)
        val annotation=rows.last()
        assertTrue(annotation.isNull("actual_total"))
        assertEquals(BuildConfig.ENGINE_SHA256,rows.first().getString("engine_sha256"))
        val replay=LabEngine(profile)
        for(row in rows.filter{it.getString("type")=="frame"}) {
            val f=row.getJSONObject("features")
            val out=replay.process(row.getLong("t_ms"),f.keys().asSequence().associateWith{f.getDouble(it).toFloat()},row.getBoolean("quality_ok"))
            assertEquals(row.getJSONObject("counts").getInt("total"),out.counts.total)
        }
        val zip=SessionLog.export(isolated)
        ZipFile(zip).use{ assertNotNull(it.getEntry(writer.file.name)) }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",zip)
        context.contentResolver.openInputStream(uri).use{ assertNotNull(it); assertEquals('P'.code,it!!.read()) }
    }
}
