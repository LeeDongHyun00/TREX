package com.trex.engine.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trex.engine.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedSessionsReplayTest {
    /** 앱이 직접 기록한 입력으로 모든 프레임의 횟수·측·시간을 다시 계산한다. 사람 정답 정확도와 별도다. */
    @Test fun liveLogsReproduceRawFeaturesAndEngineOutput() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val files=SessionLog.directory(context).listFiles()?.filter{it.extension=="jsonl"}.orEmpty()
        assertTrue("실험 앱에서 최소 한 세션을 기록해야 합니다",files.isNotEmpty())
        var frames=0; var rawFrames=0
        for(file in files) {
            val rows=file.readLines().map(::JSONObject)
            val header=rows.first()
            assertEquals(BuildConfig.ENGINE_SHA256,header.getString("engine_sha256"))
            val profile=ExerciseRepProfiles.forExercise(header.getString("exercise"))!!
            val engine=LabEngine(profile,RepMovementPattern.valueOf(header.getString("pattern")))
            val extractor=LandmarkFeatures()
            for(row in rows.filter{it.getString("type")=="frame"}) {
                val data=row.getJSONObject("features")
                val features=data.keys().asSequence().associateWith{data.getDouble(it).toFloat()}
                val xy=row.getJSONArray("xy")
                if(xy.length()==66) {
                    fun floats(a:JSONArray)=FloatArray(a.length()){if(a.isNull(it)) Float.NaN else a.getDouble(it).toFloat()}
                    val actual=extractor.extract(LandmarkFrame(floats(xy),floats(row.getJSONArray("world")),floats(row.getJSONArray("visibility")),row.getInt("width"),row.getInt("height")),profile.floor)
                    assertEquals(features.keys,actual.keys)
                    features.forEach{(k,v)->assertEquals("${file.name}/$k",v,actual.getValue(k),.0001f)}
                    rawFrames++
                } else { extractor.reset(); assertTrue(features.isEmpty()) }
                val out=engine.process(row.getLong("t_ms"),features,row.getBoolean("quality_ok"),header.getBoolean("floor_side_user_confirmed"))
                assertEquals(row.getJSONObject("counts").toString(),SessionLog.counts(out.counts).toString())
                assertEquals(row.getLong("hold_ms"),out.observedHoldMs)
                assertEquals(row.getString("status"),out.status)
                assertEquals(row.getString("view"),out.view)
                val events=row.getJSONArray("events")
                assertEquals(events.length(),out.events.size)
                out.events.forEachIndexed{i,e->assertEquals(events.getJSONObject(i).getString("side"),e.side.name)}
                frames++
            }
        }
        assertTrue(frames>0)
        File(context.getExternalFilesDir(null),"live-replay-result.json").writeText(JSONObject().put("sessions",files.size)
            .put("frames",frames).put("raw_pose_frames",rawFrames).put("matched",true).put("human_accuracy_measured",false).toString())
    }
}
