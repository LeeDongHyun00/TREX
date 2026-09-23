package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class MotionReplayTest {
    @Test fun recordedInputReplaysTheSameCompletionFramesIncludingOcclusion() {
        val counter=MotionRepCounter.forExercise("바벨 스쿼트")!!
        val frames=buildList {
            for(i in 0..30) {
                val p=if(i%10<=5) (i%10)/5f else (10-i%10)/5f
                val f=if(i==16) emptyMap() else mapOf("knee_L" to 170-90*p,"knee_R" to 170-90*p,
                    "hip_L" to 170-70*p,"hip_R" to 170-70*p,"knee_mean" to 170-90*p)
                val events=counter.onFrame(i*100L,f)
                add(MotionTraceFrame(i*100L,counter.phase.name,f,events.size,80))
            }
        }
        val result=MotionReplay.run("바벨 스쿼트",MotionContracts.VERSION,frames)
        assertTrue(result.mismatchedFrameTimes.isEmpty())
        assertEquals(counter.repTimesMs,result.events.map { it.atMs })
    }
    @Test fun setLogSerializesUnknownSideAndExactMotionInput() {
        val value=.12345679f
        val log=SetLog.build("바벨 스쿼트",emptyList(),emptyList(),"test","full","CPU",false,300,
            repCount=1,repTimesMs=listOf(700),repRecords=listOf(RepRecord(700,Float.NaN,170f,null)))
            .copy(repUnknown=1,repSides=listOf("LEFT"),motionVersion=MotionContracts.VERSION,
                motionFrames=listOf(MotionTraceFrame(100,"READY",mapOf("a" to value),0,82)))
        val json=SetLogJson.encode(log)
        assertTrue(json.contains("\"unknown\":1")); assertTrue(json.contains("\"side\":[\"LEFT\"]"))
        assertTrue(json.contains("\"a\":$value")); assertTrue(json.contains("\"valid\":[null]"))
        // Python 내보내기와 JSON 파서 검증에도 같은 직렬화물을 사용한다.
        File("build/reports/motion-log-fixture.jsonl").apply { parentFile?.mkdirs(); writeText(json+"\n") }
    }
    @Test fun exportedDeviceTraceReplaysWhenProvided() {
        val path=System.getenv("TREX_MOTION_REPLAY")
        assumeNotNull(path)
        val lines=File(path!!).readLines(Charsets.UTF_8)
        require(lines.first()=="#trex.motion-replay/1")
        val exercise=lines.first{it.startsWith("#exercise=")}.substringAfter('=')
        val version=lines.first{it.startsWith("#version=")}.substringAfter('=')
        val frames=lines.filter{!it.startsWith('#')&&it.isNotBlank()}.map { line ->
            val cells=line.split('\t')
            MotionTraceFrame(cells[0].toLong(),cells[1],cells.drop(4).associate { pair ->
                val (key,value)=pair.split('=',limit=2); key to value.toFloat()
            },cells[2].toInt(),cells[3].toLong())
        }
        val replay=MotionReplay.run(exercise,version,frames)
        assertEquals("계수 시점 불일치",emptyList<Long>(),replay.mismatchedFrameTimes)
    }
    @Test(expected=IllegalArgumentException::class) fun oldEngineVersionCannotBeSilentlyReplayed() {
        MotionReplay.run("바벨 스쿼트","old",emptyList())
    }
}
