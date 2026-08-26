package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 바닥 2D 피처 파리티/성질 테스트 (spec §25).
 *
 * 1) 파리티: research/aihub_fitness/export_floor_rules.py 가 AIHub 2D 좌표로 계산한 프레임 피처와
 *    [FloorFeatureExtractor] 결과가 같은 입력·같은 순서에서 일치 (픽스처: floor_port_fixture.txt).
 *    접지선이 스트리밍(prefix 중앙값)이라 **프레임 순서까지 상태의 일부**다.
 * 2) 좌우 반전 불변: '화면 위쪽 = 양수' 정준화로, 반대로 누워도 값이 같아야 한다.
 * 3) 접지선 선택: 움직이지 않는 접지점 쌍이 지면이 된다 (푸시업 = 손목↔발목).
 */
class FloorFeaturesTest {

    // MediaPipe 인덱스 (PostureFloor 와 동일)
    private val idx = mapOf(
        "Nose" to 0, "LEar" to 7, "REar" to 8, "LShoulder" to 11, "RShoulder" to 12,
        "LElbow" to 13, "RElbow" to 14, "LWrist" to 15, "RWrist" to 16,
        "LHip" to 23, "RHip" to 24, "LKnee" to 25, "RKnee" to 26, "LAnkle" to 27, "RAnkle" to 28,
    )
    private val mirrorPairs = listOf(7 to 8, 11 to 12, 13 to 14, 15 to 16, 23 to 24, 25 to 26, 27 to 28)
    private val notMirrorSafe = setOf("elbow_ang", "elbow_width", "shoulder_asym2d")

    private fun xyOf(points: Map<String, Pair<Double, Double>>): FloatArray {
        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        for ((name, p) in points) {
            val i = idx.getValue(name)
            xy[i * 2] = p.first.toFloat()
            xy[i * 2 + 1] = p.second.toFloat()
        }
        return xy
    }

    // ---------- 1) 연구 코드와의 파리티 ----------

    private data class FixtureFrame(val xy: FloatArray, val expected: Map<String, Float>)

    private fun loadFixture(): Map<String, List<FixtureFrame>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("floor_port_fixture.txt")
            ?: error("floor_port_fixture.txt 없음 — export_floor_rules.py 를 먼저 실행하세요")
        val clips = LinkedHashMap<String, MutableList<FixtureFrame>>()
        var clip = ""
        var xy = FloatArray(MP_LANDMARK_COUNT * 2)
        var expected = HashMap<String, Float>()
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val p = line.split(" ")
            when (p[0]) {
                "CLIP" -> {
                    clip = p.drop(1).joinToString(" ")
                    clips[clip] = ArrayList()
                }
                "FRAME" -> {
                    xy = FloatArray(MP_LANDMARK_COUNT * 2)
                    expected = HashMap()
                }
                "P" -> {
                    val i = idx.getValue(p[1])
                    xy[i * 2] = p[2].toFloat()
                    xy[i * 2 + 1] = p[3].toFloat()
                }
                "F" -> expected[p[1]] = p[2].toFloat()
                "ENDFRAME" -> clips.getValue(clip).add(FixtureFrame(xy, expected))
            }
        }
        return clips
    }

    @Test
    fun matchesResearchFeatures() {
        val clips = loadFixture()
        assertTrue("픽스처에 클립이 없음", clips.size >= 3)
        var checked = 0
        for ((name, frames) in clips) {
            val ex = FloorFeatureExtractor()
            frames.forEachIndexed { t, fr ->
                // 픽스처는 px 좌표 → w=h=1 로 넘기면 정규화×크기 = px 그대로
                val got = ex.compute(fr.xy, null, 1, 1)
                for ((feat, want) in fr.expected) {
                    val v = got[feat] ?: error("$name f$t: $feat 누락")
                    val tol = if (feat.endsWith("_ang")) 0.02f else maxOf(2e-4f, abs(want) * 1e-3f)
                    assertTrue("$name f$t $feat: got=$v want=$want", abs(v - want) <= tol)
                    checked++
                }
            }
        }
        assertTrue("검증 수가 너무 적음: $checked", checked > 500)
    }

    // ---------- 2) 좌우 반전 불변 ----------

    private fun plankPoints(hipY: Double): Map<String, Pair<Double, Double>> = mapOf(
        "Nose" to (95.0 to 388.0), "LEar" to (110.0 to 392.0), "REar" to (112.0 to 390.0),
        "LShoulder" to (180.0 to 400.0), "RShoulder" to (182.0 to 396.0),
        "LElbow" to (185.0 to 450.0), "RElbow" to (188.0 to 448.0),
        "LWrist" to (190.0 to 500.0), "RWrist" to (193.0 to 498.0),
        "LHip" to (450.0 to hipY), "RHip" to (452.0 to hipY - 3),
        "LKnee" to (610.0 to 462.0), "RKnee" to (612.0 to 460.0),
        "LAnkle" to (780.0 to 500.0), "RAnkle" to (782.0 to 498.0),
    )

    @Test
    fun mirrorInvariantAfterCanonicalization() {
        val w = 1000
        val h = 800
        val frames = listOf(430.0, 445.0, 455.0, 440.0, 432.0)   // 골반이 오르내리는 플랭크
        val exA = FloorFeatureExtractor()
        val exB = FloorFeatureExtractor()
        frames.forEach { hipY ->
            val pts = plankPoints(hipY)
            val a = exA.compute(xyOf(pts), null, w, h)
            // 반전: x → w−x, 좌우 관절 교환 (반대 방향으로 누운 같은 사람)
            val xy = xyOf(pts)
            for (i in 0 until MP_LANDMARK_COUNT) xy[i * 2] = w - xy[i * 2]
            for ((l, r) in mirrorPairs) {
                for (k in 0..1) {
                    val t = xy[l * 2 + k]; xy[l * 2 + k] = xy[r * 2 + k]; xy[r * 2 + k] = t
                }
            }
            val b = exB.compute(xy, null, w, h)
            for ((feat, va) in a) {
                if (feat in notMirrorSafe) continue
                val vb = b[feat] ?: error("$feat 누락")
                assertTrue("$feat: 원본=$va 반전=$vb", abs(va - vb) <= maxOf(1e-3f, abs(va) * 1e-3f))
            }
        }
    }

    // ---------- 3) 접지선 선택과 부호 ----------

    @Test
    fun groundLineUsesStaticContactsAndUpIsPositive() {
        val w = 1000
        val h = 800
        val ex = FloorFeatureExtractor()
        var last: Map<String, Float> = emptyMap()
        // 푸시업: 손목·발목은 고정, 골반·어깨가 오르내림 → 접지선 = 손목↔발목
        listOf(430.0, 470.0, 435.0, 468.0, 433.0).forEach { hipY ->
            val pts = plankPoints(hipY).toMutableMap()
            pts["LShoulder"] = 180.0 to (hipY - 35); pts["RShoulder"] = 182.0 to (hipY - 38)
            last = ex.compute(xyOf(pts), null, w, h)
        }
        // 골반(y≈433)은 손목(500)↔발목(500) 선보다 화면 위 → hip_ground > 0
        val hipGround = last.getValue("hip_ground")
        assertTrue("hip_ground=$hipGround", hipGround > 0f)
        // 어깨는 골반보다 더 위 → shoulder_ground > hip_ground
        assertTrue(last.getValue("shoulder_ground") > hipGround)
        assertEquals(5, ex.frameCount)
        ex.reset()
        assertEquals(0, ex.frameCount)
    }

    // ---------- 4) 바닥 규칙 자산과 코칭 문구 ----------

    @Test
    fun floorCueCatalogCoversExportedConditions() {
        val conds = listOf(
            "고개 젖힘/숙임 여부", "고개 들지 않기", "고개 숙임 여부", "시선 배꼽 고정",
            "견갑골이 지면으로부터 충분히 올라옴", "견갑골이 지면으로부터 충분히올라옴",
            "수축시 무릎부터 어깨까지 일자", "몸통과 엉덩이의 정렬 유지", "허벅지와 종아리 각도 고정",
            "다리와 지면 사이 적당한 거리", "무릎 너무 굽히지 않음", "경추 중립 또는 후인(retraction) 유지",
            "손의 위치 가슴 중앙 여부", "가슴의 충분한 이동",
        )
        for (c in conds) {
            val rule = PostureRule(
                id = "floor|x|$c", exercise = "푸시업", condition = c, subtype = null, status = RuleStatus.BETA,
                reason = null, feature = "head_trunk_ang__mean", baseFeature = "head_trunk_ang", stat = "mean",
                family = "floor2d", op = ">", threshold = 100f, view = "C", viewDesc = "", cvAuc = 0.8f,
                cvBalacc = 0.7f, sampleN = 100, mirrorSafe = true, cautions = emptyList(),
            )
            val cue = CoachCues.cueFor(rule)
            assertTrue("바닥 조건 문구 누락: $c → ${cue.habit}", !cue.habit.contains(c))
            assertTrue(cue.habit.startsWith("처음부터"))
        }
    }
}
