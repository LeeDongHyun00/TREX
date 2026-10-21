package trex.replay

import com.example.trex_kotlin.posture.FloorChain
import com.example.trex_kotlin.posture.FloorFeatureExtractor
import com.example.trex_kotlin.posture.FloorProfile
import com.example.trex_kotlin.posture.PlankHoldClock
import com.example.trex_kotlin.posture.Vec3
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 재생기 바닥 경로(spec §99) — 앱 PostureLive 바닥 분기와 **같은 함수**(FloorFeatureExtractor.computeForExercise)로 피처를 만들고, 크런치·레그 레이즈는
 * FloorCycleTracker(RepCounter.forSession floor = true), 플랭크는 PlankHoldClock 으로 돈다. 합성 측면 좌표(1000×1000, 왼쪽이 카메라 쪽).
 */
class FloorReplayTest {
    private fun temp(text: String): File = File.createTempFile("floor", ".cap").apply { deleteOnExit(); writeText(text) }

    /** 33점 정규화 좌표·가시성 — 가까운 쪽(왼쪽, 짝수 인덱스 아님 주의: MediaPipe 왼쪽 = 홀수 11·13…) 0.9, 먼 쪽은 같은 자리 + 0.01 아래, 가시성 0.3. */
    private fun body(near: Map<Int, Pair<Float, Float>>): List<FloatArray> = (0 until 33).map { i ->
        val leftIdx = if (i in 11..32 && i % 2 == 0) i - 1 else if (i == 8) 7 else i   // 오른쪽 관절은 왼쪽 짝의 좌표를 빌린다
        val p = near[leftIdx] ?: near[0]!!
        val far = (i in 11..32 && i % 2 == 0) || i == 8
        if (far) floatArrayOf(p.first, p.second + 0.01f, 0.3f) else floatArrayOf(p.first, p.second, 0.9f)
    }

    /** 누워서 상체(어깨–골반 현)를 [lift]° 든 크런치 — 골반 (0.5, 0.7), 발목 (0.8, 0.7), 무릎 굽힘. */
    private fun crunch(lift: Float): List<FloatArray> {
        val r = Math.toRadians(lift.toDouble())
        val sx = (0.5 - 0.2 * cos(r)).toFloat(); val sy = (0.7 - 0.2 * sin(r)).toFloat()
        val ex = (0.5 - 0.27 * cos(r + 0.15)).toFloat(); val ey = (0.7 - 0.27 * sin(r + 0.15)).toFloat()
        return body(mapOf(0 to (ex - 0.02f to ey), 7 to (ex to ey), 11 to (sx to sy), 13 to (sx + 0.05f to sy + 0.05f), 15 to (sx + 0.1f to sy + 0.02f),
            23 to (0.5f to 0.7f), 25 to (0.66f to 0.56f), 27 to (0.8f to 0.7f)))
    }

    /** 측면 전완 플랭크 — 어깨·골반·무릎·발목 일직선(골반 오프셋 0), 팔꿈치는 어깨 아래. */
    private fun plank(): List<FloatArray> = body(mapOf(0 to (0.22f to 0.52f), 7 to (0.25f to 0.48f), 11 to (0.3f to 0.5f), 13 to (0.3f to 0.62f), 15 to (0.4f to 0.62f),
        23 to (0.55f to 0.5f), 25 to (0.7f to 0.5f), 27 to (0.85f to 0.5f)))

    private fun fLine(t: Long, pts: List<FloatArray>?): String =
        if (pts == null) "F\t$t\t0" else "F\t$t\t1\t" + pts.mapIndexed { i, p -> "$i:${p[0]},${p[1]},${p[2]},nan,nan,nan,nan" }.joinToString("\t")

    private fun cap(exercise: String, frames: List<List<FloatArray>?>, up: Boolean = false, t0: Long = 0L, extra: String = ""): String =
        "H\texercise=$exercise\tfloor=1\timageW=1000\timageH=1000$extra\n" + frames.mapIndexed { k, pts ->
            val t = t0 + k * 300L
            (if (up && pts != null) "U\t$t\t0,1,0\n" else "") + fLine(t, pts)
        }.joinToString("\n") + "\n"

    private val crunchSet: List<List<FloatArray>?> = List(6) { crunch(0f) } +
        List(3) { listOf(6f, 12f, 18f, 24f, 18f, 12f, 6f, 0f, 0f, 0f).map { crunch(it) } }.flatten()

    @Test
    fun `floor dump is the app function computeForExercise with gravity up from the U line`() {
        val capture = readCapture(temp(cap("크런치", crunchSet.take(4), up = true)))
        val out = File.createTempFile("dump", ".jsonl").apply { deleteOnExit() }
        dumpFeatures(capture, out)
        val lines = out.readLines()
        assertEquals(4, lines.size)
        // 같은 입력으로 앱 함수를 직접 부른 값과 같다(추출기는 세트 단위 상태라 새로 만든다)
        val ex = FloorFeatureExtractor()
        for ((k, f) in capture.frames.withIndex()) {
            val vis = FloatArray(33) { f.image[it]!![2] }
            val xy = FloatArray(66) { f.image[it / 2]!![it % 2] }
            val direct = ex.computeForExercise("크런치", xy, vis, 1000, 1000, Vec3(0f, 1f, 0f), f.tMs)
            for ((key, v) in direct) assertTrue("$key in dump line $k", lines[k].contains("\"$key\":$v"))
        }
        assertTrue(lines[0].contains("\"${FloorChain.UP_OK}\":1.0"))
        assertTrue(lines[0].contains("\"${FloorChain.AXIS_H}\""))
        assertFalse("서서 하는 3D 피처를 쓰지 않는다", lines[0].contains("\"knee_mean\""))
        // U 줄이 없으면 중력 모름 — 롤 의존 피처 유보
        val noUp = readCapture(temp(cap("크런치", crunchSet.take(1))))
        val o2 = File.createTempFile("dump", ".jsonl").apply { deleteOnExit() }
        dumpFeatures(noUp, o2)
        val l2 = o2.readLines().single()
        assertTrue(l2.contains("\"${FloorChain.UP_OK}\":0.0")); assertFalse(l2.contains("\"${FloorChain.AXIS_H}\""))
        assertTrue(l2.contains("\"${FloorChain.TRUNK_LIFT}\""))
    }

    @Test
    fun `crunch landmark capture counts with the floor cycle tracker and reports floor details`() {
        val capture = readCapture(temp(cap("크런치", crunchSet, up = true)))
        val line = run(Job("c", "x.cap", "크런치", "live", null, null, floor = true), capture, null)
        assertTrue(line, line.contains("\"engine\":\"floorcycle_v1_beta\""))
        assertTrue(line, line.contains("\"reps\":3,"))
        assertTrue(line, line.contains("\"floorEnabled\":[\"arms_only\",\"neck_only\",\"shallow\",\"sit_up\"]"))   // 2026-10-08 부터 shallow 기본 켬
        assertTrue(line, line.contains("\"floorLying\":{"))
        assertTrue(line, Regex("\"floorReps\":\\[\\[").containsMatchIn(line))
        assertTrue(line, line.contains("\"upFrames\":${crunchSet.size}"))
        // 진단 구성(11열) — 사유를 바꿔도 사이클 분할은 같다
        val all = run(Job("c", "x.cap", "크런치", "live", null, null, floor = true, floorEnabled = "all"), capture, null)
        assertTrue(all, all.contains("\"floorEnabled\":[\"arms_only\",\"neck_only\",\"shallow\",\"sit_up\"]"))
        assertTrue(all, all.contains("\"reps\":3,"))
    }

    @Test
    fun `floor enabled spec parses default all none and lists`() {
        val p = FloorProfile.LEG_RAISE
        assertNull(floorEnabledSet(null, p)); assertNull(floorEnabledSet("default", p)); assertNull(floorEnabledSet("all", null))
        assertEquals(p.reasons.toSet(), floorEnabledSet("all", p))
        assertEquals(emptySet<String>(), floorEnabledSet("none", p))
        assertEquals(setOf("knee_bent"), floorEnabledSet("knee_bent", p))
        try { floorEnabledSet("neck_only", p); fail("레그 레이즈에 없는 사유") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `plank runs the hold clock - undetected frames are out of view`() {
        val frames: List<List<FloatArray>?> = List(10) { plank() } + List(5) { null }
        val capture = readCapture(temp(cap("플랭크", frames, up = true)))
        val line = run(Job("p", "x.cap", "플랭크", "live", null, null, floor = true), capture, null)
        assertTrue(line, line.contains("\"engine\":\"${PlankHoldClock.ENGINE}\""))
        assertTrue(line, line.contains("\"heldMs\":2700,"))
        assertTrue(line, line.contains("[\"START\",0,null]"))
        assertTrue(line, line.contains("\"firstStop\":[2700,\"out_of_view\"]"))
        assertTrue(line, line.contains("\"gateFrames\":10,"))
        assertTrue(line, line.contains("\"frameReasons\":{\"hold\":10,\"out_of_view\":5}"))
    }

    @Test
    fun `plank set log replay rebuilds the undetected bins from the logged work start like the app`() {
        // 리뷰 2026-10-07: 세트 로그에는 검출된 칸만 남는다(t0 = 첫 검출). WORK 시작 뒤 21 s 동안 화면 밖이었다가 들어온 세트 — 앱은 20 s 에 시계로 넘어갔는데
        // 재생은 첫 검출을 시작으로 잡아 카메라 시간으로 나왔다
        val frames: List<List<FloatArray>?> = List(20) { plank() }
        val noMeta = run(Job("p", "x.cap", "플랭크", "live", null, null, floor = true), readCapture(temp(cap("플랭크", frames, up = true))), null)
        assertTrue(noMeta, noMeta.contains("\"source\":\"camera\"")); assertTrue(noMeta, noMeta.contains("\"syntheticNullFrames\":0,"))
        val extra = "\tloggedHoldStartMs=-21000\tloggedHoldEndMs=${19 * 300 + 900}\tloggedHoldSource=clock"
        val line = run(Job("p", "x.cap", "플랭크", "live", null, null, floor = true), readCapture(temp(cap("플랭크", frames, up = true, extra = extra))), null)
        assertTrue(line, line.contains("\"source\":\"clock\"")); assertTrue(line, line.contains("\"paritySource\":true"))
        assertTrue("WORK 시작 20 s 뒤(첫 칸 -21000 부터 300 ms 칸) 폴백 — $line", line.contains("[\"FALLBACK\",-900,null]"))
        assertTrue(line, line.contains("\"syntheticNullFrames\":73,"))       // 앞 70칸 + 끝 3칸
        assertTrue(line, line.contains("\"holdStartMs\":-21000,"))
        // 같은 입력을 앱처럼 직접 돌린 시계와 같다(사람 없음 칸 = onFrame(t, null))
        val app = PlankHoldClock(); app.start(-21_000)
        var t = -21_000L; while (t <= -150) { app.onFrame(t, null); t += 300 }
        val ex = FloorFeatureExtractor()
        for (k in 0 until 20) {
            val pts = plank(); val vis = FloatArray(33) { pts[it][2] }; val xy = FloatArray(66) { pts[it / 2][it % 2] }
            app.onFrame(k * 300L, ex.computeForExercise("플랭크", xy, vis, 1000, 1000, Vec3(0f, 1f, 0f), k * 300L))
        }
        for (e in listOf(19 * 300L + 300, 19 * 300L + 600, 19 * 300L + 900)) app.onFrame(e, null)
        assertTrue(line, line.contains("\"heldMs\":${app.heldMs},")); assertEquals(PlankHoldClock.SOURCE_CLOCK, app.source)
        // 일시정지 구간(로그 segments 의 pause)은 시계 밖 — 그 사이를 화면 밖으로 채우지 않는다. 기록: 0~1800 플랭크, (일시정지) 5400~8100 플랭크
        val times = (0L..1800L step 300) + (5400L..8100L step 300)
        fun gapCap(extra: String) = "H\texercise=플랭크\tfloor=1\timageW=1000\timageH=1000$extra\n" +
            times.joinToString("\n") { t -> "U\t$t\t0,1,0\n" + fLine(t, plank()) } + "\n"
        val noPause = run(Job("p", "x.cap", "플랭크", "live", null, null, floor = true), readCapture(temp(gapCap("\tloggedHoldStartMs=0"))), null)
        assertTrue("일시정지를 모르면 그 사이가 화면 밖 — $noPause", noPause.contains("\"firstStop\":[1800,\"out_of_view\"]"))
        val paused = run(Job("p", "x.cap", "플랭크", "live", null, null, floor = true),
            readCapture(temp(gapCap("\tloggedHoldStartMs=0\tloggedHoldPauses=1900-5300"))), null)
        assertTrue(paused, paused.contains("\"firstStop\":null")); assertTrue(paused, paused.contains("\"pause\"]"))
        assertTrue(paused, paused.contains("\"syntheticNullFrames\":0,"))
    }

    @Test
    fun `floor clips replays each clip with a fresh engine`() {
        // 두 클립(5 s 틈) — 둘째 클립은 앉은 자세에서 시작해 누운 기준이 첫 클립에서 넘어오지 않아야 한다
        val first = cap("크런치", crunchSet, up = true)
        val secondStart = (crunchSet.size - 1) * 300L + 5_000L
        val second = List(4) { crunch(85f) }.mapIndexed { k, pts -> val t = secondStart + k * 300L; "U\t$t\t0,1,0\n" + fLine(t, pts) }.joinToString("\n")
        val capture = readCapture(temp(first + second + "\n"))
        val out = File.createTempFile("clips", ".jsonl").apply { deleteOnExit() }
        assertEquals(2, floorClips(capture, out, null))
        val lines = out.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("\"i\":0}") && lines[0].contains("\"reps\":3,"))
        assertTrue(lines[1], lines[1].contains("\"i\":1}") && lines[1].contains("\"floorLying\":null") && lines[1].contains("\"reps\":0,"))
        assertNotNull(Regex("\"floorBase\":null").find(lines[1]))
    }
}
