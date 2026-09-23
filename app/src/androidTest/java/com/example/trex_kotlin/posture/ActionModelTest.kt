package com.example.trex_kotlin.posture

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ActionModelTest {
    @Test fun sessionRunnerLoadsVerifiedBundleAndKeepsRawCaptureOptIn() {
        val i=InstrumentationRegistry.getInstrumentation()
        val f=i.context.assets.open("action_packet_fixture.txt").bufferedReader().readLines()
            .map { line -> line.trim().split(" ").map(String::toFloat).toFloatArray() }
        val engine=ActionShadowEngine(i.targetContext)
        fun offerAndWait(n: Int) {
            val packet=PosePacketV2(n*300L,n*300_000_000L,1,640,480,f[1],null,f[2],f[3],Vec3(0f,1f,0f),false,false)
            val before=engine.snapshot(0).observations.size
            engine.offer(packet,"바벨 스쿼트",InferencePolicy.THERMAL_NONE)
            val deadline=System.currentTimeMillis()+10000
            while(engine.snapshot(0).observations.size<=before && System.currentTimeMillis()<deadline) Thread.sleep(20)
            assertTrue("worker completion",engine.snapshot(0).observations.size>before)
        }
        try {
            for(n in 1..5) offerAndWait(n)
            var log=engine.snapshot(0)
            assertEquals("aihub26-context-shadow-1",log.bundleId)
            assertTrue(log.rawFrames.isEmpty()); assertFalse(log.captureEnabled)
            assertTrue(log.observations.none { it.state==ActionState.UNAVAILABLE })
            assertTrue(log.observations.all { !it.canSpeak && !it.canAffectCount })
            engine.setCaptureEnabled(true); offerAndWait(6)
            assertEquals(1,engine.snapshot(0).rawFrames.size)
            engine.setCaptureEnabled(false)
            assertTrue(engine.snapshot(0).rawFrames.isEmpty())
            engine.resetSession(); offerAndWait(7)
            log=engine.snapshot(0)
            assertEquals(ActionState.WARMUP,log.observations.single().state)
        } finally { engine.close() }
    }

    @Test fun shippedModelMatchesPythonGoldenOnAndroidCpu() {
        val i=InstrumentationRegistry.getInstrumentation()
        val manifest=i.targetContext.assets.open("posture/action/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val fixture=i.context.assets.open("action_model_fixture.txt").bufferedReader().readLines()
        val bytes=i.targetContext.assets.open("posture/action/exercise_context.tflite").use { it.readBytes() }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals(manifest.getString("sha256"),hash); assertEquals(fixture[0],hash)
        assertEquals("shadow",manifest.getString("mode")); assertFalse(manifest.getBoolean("can_speak"))
        val values=fixture[1].split(" ").map(String::toFloat).toFloatArray()
        val expected=fixture[2].split(" ").map(String::toFloat).toFloatArray()
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        val input=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(values)
        Interpreter(model,Interpreter.Options().setNumThreads(1)).use { runner ->
            val output=Array(1){FloatArray(27)}
            runner.run(input,output)
            assertArrayEquals(expected,output[0],.15f)
            assertEquals(expected.indices.maxBy{expected[it]},output[0].indices.maxBy{output[0][it]})
        }
    }
}
