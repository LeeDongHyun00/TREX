package trex.replay

import com.example.trex_kotlin.posture.Vec3
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 랜드마크 캡처의 U 줄(프레임별 up, spec §61 휴대폰 검증 로그)과 --dump-features.
 * 핵심 약속 두 가지: (1) U 줄이 없는 옛 캡처는 전과 똑같이 SCREEN_UP 으로 재생된다, (2) dump 는 재생 경로와 같은 함수의 결과다.
 */
class ReplayCaptureTest {

    // 선 자세(MediaPipe 월드 좌표 원본 부호: y 아래, z 카메라 쪽이 음수). 무릎만 knee 로 굽힌다 — 값은 반복 모양이 아니라 형식 검사용.
    private fun standing(kneeBendM: Float): List<FloatArray> {
        val base = HashMap<Int, FloatArray>()
        base[0] = floatArrayOf(0f, -0.62f, -0.08f)
        for (i in 1..10) base[i] = floatArrayOf(if (i % 2 == 1) 0.03f else -0.03f, -0.64f, -0.06f)
        base[11] = floatArrayOf(0.18f, -0.48f, 0f); base[12] = floatArrayOf(-0.18f, -0.48f, 0f)
        base[13] = floatArrayOf(0.20f, -0.20f, 0f); base[14] = floatArrayOf(-0.20f, -0.20f, 0f)
        for (i in listOf(15, 17, 19, 21)) base[i] = floatArrayOf(0.21f, 0.05f, -0.02f)
        for (i in listOf(16, 18, 20, 22)) base[i] = floatArrayOf(-0.21f, 0.05f, -0.02f)
        base[23] = floatArrayOf(0.10f, 0f, 0f); base[24] = floatArrayOf(-0.10f, 0f, 0f)
        base[25] = floatArrayOf(0.11f, 0.43f, -kneeBendM); base[26] = floatArrayOf(-0.11f, 0.43f, -kneeBendM)
        base[27] = floatArrayOf(0.11f, 0.85f, 0.02f); base[28] = floatArrayOf(-0.11f, 0.85f, 0.02f)
        base[29] = floatArrayOf(0.11f, 0.88f, 0.06f); base[30] = floatArrayOf(-0.11f, 0.88f, 0.06f)
        base[31] = floatArrayOf(0.12f, 0.90f, -0.08f); base[32] = floatArrayOf(-0.12f, 0.90f, -0.08f)
        return (0 until 33).map { base.getValue(it) }
    }

    private fun fLine(t: Long, world: List<FloatArray>?, vis: Float = 0.9f): String {
        if (world == null) return "F\t$t\t0"
        val cells = world.mapIndexed { i, w -> "$i:${0.5f + w[0] / 2f},${0.4f + w[1] / 2f},$vis,nan,${w[0]},${w[1]},${w[2]}" }
        return "F\t$t\t1\t" + cells.joinToString("\t")
    }

    private fun temp(text: String): File = File.createTempFile("cap", ".cap").apply { deleteOnExit(); writeText(text) }

    @Test
    fun `no U line - frames keep SCREEN_UP and replay the same as an explicit screen up`() {
        val cap = readCapture(temp("H\tsource=test\n" + fLine(0, standing(0.10f)) + "\n" + fLine(300, null) + "\n"))
        assertEquals(2, cap.frames.size)
        assertNull(cap.frames[0].up)
        val implicit = frameFeatures(cap.frames[0], FrameStats())
        val explicit = frameFeatures(readCapture(temp("U\t0\t0,1,0\n" + fLine(0, standing(0.10f)) + "\n")).frames[0], FrameStats())
        assertEquals(implicit, explicit)
        assertNull(frameFeatures(cap.frames[1], FrameStats()))
    }

    @Test
    fun `U line applies only to the next F line and changes up-dependent features, not joint angles`() {
        val tilt = Vec3(0.3f, 0.95f, 0.1f)
        val text = "U\t0\t${tilt.x},${tilt.y},${tilt.z}\n" + fLine(0, standing(0.10f)) + "\n" + fLine(300, standing(0.10f)) + "\n"
        val cap = readCapture(temp(text))
        assertEquals(tilt, cap.frames[0].up)
        assertNull(cap.frames[1].up)
        val tilted = frameFeatures(cap.frames[0], FrameStats())!!
        val screen = frameFeatures(cap.frames[1], FrameStats())!!
        assertEquals(screen.getValue("knee_mean"), tilted.getValue("knee_mean"), 1e-4f)
        assertNotEquals(screen.getValue("torso_incl"), tilted.getValue("torso_incl"), 1e-3f)
        // 앱처럼 방향 피처도 같은 사전에 있다(ViewEstimator.frameFeatures)
        assertNotNull(screen["view_cos"])
        assertNotNull(screen["view_sin"])
    }

    @Test(expected = IllegalStateException::class)
    fun `U line whose time does not match the next F line is rejected`() {
        readCapture(temp("U\t10\t0,1,0\n" + fLine(0, standing(0.1f)) + "\n"))
    }

    @Test
    fun `dump-features writes one line per F line with the replay frameFeatures values`() {
        val text = "H\tsource=test\nU\t0\t0.05,0.99,0.02\n" + fLine(0, standing(0.12f)) + "\n" + fLine(310, null) + "\n" +
            fLine(620, standing(0.25f), vis = 0.2f) + "\n"
        val file = temp(text)
        val out = File.createTempFile("dump", ".jsonl").apply { deleteOnExit() }
        assertEquals(3, dumpFeatures(readCapture(file), out))
        val lines = out.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("{\"t\":0,\"detected\":true,\"features\":{"))
        val expected = frameFeatures(readCapture(file).frames[0], FrameStats())!!
        for ((k, v) in expected) assertTrue("$k missing", lines[0].contains("\"$k\":${num(v)}"))
        assertEquals("{\"t\":310,\"detected\":false,\"features\":{}}", lines[1])
        // 가시성 < 0.5 인 관절은 앱처럼 버려진다 → 사람은 있지만 피처 없음
        assertEquals("{\"t\":620,\"detected\":true,\"features\":{}}", lines[2])
    }

    @Test
    fun `replay result carries the counter's effective config`() {
        val file = temp("H\tsource=test\n" + (0 until 20).joinToString("\n") { fLine(it * 300L, standing(if (it % 6 < 3) 0.05f else 0.3f)) } + "\n")
        val line = run(Job("x", file.path, "바벨 스쿼트", "live", null, null), readCapture(file), null)
        assertTrue(line, line.contains("\"refractoryMs\":1200") && line.contains("\"maxGapMs\":1500") && line.contains("\"completeOnReturn\":true"))
        assertFalse(line.contains("\"error\""))
    }
}
