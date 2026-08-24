package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Kotlin 포팅 파리티 테스트.
 *
 * 연구 코드(research/aihub_fitness/features.py)가 MediaPipe world landmark 로 계산한 프레임 피처와
 * 앱의 [PoseFrame.features] 결과가 같은 입력에서 일치하는지 검증한다.
 * 픽스처 생성: research/aihub_fitness/export_port_fixture.py
 */
class PostureCoreParityTest {

    private data class Case(
        val name: String,
        val joints: Map<String, Vec3?>,
        val expected: Map<String, Float>,
    )

    private fun loadCases(): List<Case> {
        val stream = javaClass.classLoader!!.getResourceAsStream("posture_port_fixture.txt")
            ?: error("posture_port_fixture.txt 없음 — export_port_fixture.py 를 먼저 실행하세요")
        val cases = ArrayList<Case>()
        var name = ""
        var joints = HashMap<String, Vec3?>()
        var expected = HashMap<String, Float>()
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(" ")
            when (parts[0]) {
                "CASE" -> {
                    name = parts.drop(1).joinToString(" ")
                    joints = HashMap()
                    expected = HashMap()
                }
                "J" -> joints[parts[1]] = Vec3(parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
                "F" -> expected[parts[1]] = parts[2].toFloat()
                "END" -> cases += Case(name, joints, expected)
            }
        }
        return cases
    }

    /** 각도는 도 단위(허용 0.05°), 정규화 거리는 상대 오차 허용. */
    private fun tolerance(feature: String, expected: Float): Float {
        val angular = feature.startsWith("knee_") && !feature.startsWith("knee_out") &&
            !feature.startsWith("knee_h") && !feature.startsWith("knee_lat") &&
            !feature.startsWith("knee_gap") && !feature.startsWith("knee_fwd") &&
            !feature.startsWith("knee_elbow") ||
            feature.startsWith("hip_L") || feature.startsWith("hip_R") || feature.startsWith("hip_mean") ||
            feature.startsWith("elbow_L") || feature.startsWith("elbow_R") || feature.startsWith("elbow_mean") ||
            feature.startsWith("elbow_min") || feature.startsWith("elbow_max") ||
            feature.startsWith("shoulder_L") || feature.startsWith("shoulder_R") ||
            feature.startsWith("torso_") || feature.startsWith("head_") || feature.startsWith("face_") ||
            feature.startsWith("forearm_vert") || feature.startsWith("upperarm_vert") ||
            feature.startsWith("foot_pitch") || feature.startsWith("kneefoot") ||
            feature.startsWith("ankle_L") || feature.startsWith("ankle_R") || feature.startsWith("wrist_")
        return if (angular) 0.05f else maxOf(1e-4f, abs(expected) * 1e-3f)
    }

    @Test
    fun featuresMatchResearchImplementation() {
        val cases = loadCases()
        assertTrue("픽스처 케이스가 있어야 한다", cases.size >= 10)

        var compared = 0
        val mismatches = ArrayList<String>()
        val missing = LinkedHashSet<String>()

        for (case in cases) {
            val actual = PoseFrame(case.joints).features()
            for ((feature, exp) in case.expected) {
                val act = actual[feature]
                if (act == null) {
                    missing += feature
                    continue
                }
                compared++
                val tol = tolerance(feature, exp)
                if (abs(act - exp) > tol) {
                    mismatches += "${case.name} $feature: expected=$exp actual=$act (tol=$tol)"
                }
            }
        }

        // 연구 코드에만 있고 Kotlin 이 의도적으로 계산하지 않는 피처는 없어야 한다
        // (spine_*, kneefoot_thigh 는 픽스처 생성 시 제외됨)
        assertTrue("Kotlin 에서 누락된 피처: $missing", missing.isEmpty())
        assertTrue("비교된 피처가 충분해야 한다 (실제 $compared)", compared > 3000)
        assertEquals("불일치 ${mismatches.size}건:\n" + mismatches.take(20).joinToString("\n"), 0, mismatches.size)
    }

    /** 로드리게스 회전. */
    private fun rotate(v: Vec3, axis: Vec3, angleRad: Float): Vec3 {
        val k = axis.unit()!!
        val c = kotlin.math.cos(angleRad)
        val s = kotlin.math.sin(angleRad)
        return v * c + (k cross v) * s + k * ((k dot v) * (1f - c))
    }

    /**
     * 중력축 일반화 검증: 장면(관절)과 up 을 같은 회전으로 돌리면 모든 피처가 불변이어야 한다.
     * 폰이 기울어도(롤·피치) IMU up 을 쓰면 값이 보존된다는 뜻이다.
     */
    @Test
    fun featuresAreInvariantWhenSceneAndUpRotateTogether() {
        val cases = loadCases()
        val rotations = listOf(
            Triple(Vec3(0f, 0f, 1f), 30f, "롤 30°"),      // 카메라 축 회전 = 폰 롤
            Triple(Vec3(1f, 0f, 0f), 20f, "피치 20°"),    // 폰을 뒤로 기울임
            Triple(Vec3(0.3f, 0.2f, 1f), 47f, "복합 47°"),
        )
        var compared = 0
        val mismatches = ArrayList<String>()
        for (case in cases.take(15)) {
            val base = PoseFrame(case.joints).features()
            for ((axis, deg, label) in rotations) {
                val rad = (deg * Math.PI / 180.0).toFloat()
                val rotatedJoints = case.joints.mapValues { (_, v) -> v?.let { rotate(it, axis, rad) } }
                val rotatedUp = rotate(Vec3(0f, 1f, 0f), axis, rad)
                val rotated = PoseFrame(rotatedJoints, rotatedUp).features()
                for ((feature, expected) in base) {
                    val actual = rotated[feature]
                    if (actual == null) {
                        mismatches += "${case.name} [$label] $feature: 회전 후 누락"
                        continue
                    }
                    compared++
                    // 회전 누적 부동소수 오차를 감안해 파리티보다 약간 넉넉하게
                    val tol = maxOf(0.15f, abs(expected) * 3e-3f)
                    if (abs(actual - expected) > tol) {
                        mismatches += "${case.name} [$label] $feature: base=$expected rotated=$actual (tol=$tol)"
                    }
                }
            }
        }
        assertTrue("비교된 피처가 충분해야 한다 (실제 $compared)", compared > 3000)
        assertEquals("회전 불변성 위반 ${mismatches.size}건:\n" + mismatches.take(15).joinToString("\n"), 0, mismatches.size)
    }

    /** IMU 중력 → world up 변환 (PostureOrientation). */
    @Test
    fun gravityMapsToWorldUp() {
        fun assertVec(msg: String, expected: Vec3, actual: Vec3?) {
            assertTrue("$msg: null", actual != null)
            assertEquals("$msg x", expected.x, actual!!.x, 1e-4f)
            assertEquals("$msg y", expected.y, actual.y, 1e-4f)
            assertEquals("$msg z", expected.z, actual.z, 1e-4f)
        }
        val rot0 = android.view.Surface.ROTATION_0
        // 폰을 세로로 세움: 중력은 기기 아래(-y) → up 은 화면 위 = world (0,1,0) (기존 가정과 동일)
        assertVec("세로 거치/후면", Vec3(0f, 1f, 0f), gravityUpInWorld(Vec3(0f, -9.81f, 0f), rot0, false))
        assertVec("세로 거치/전면", Vec3(0f, 1f, 0f), gravityUpInWorld(Vec3(0f, -9.81f, 0f), rot0, true))
        // 폰을 반시계로 90° 눕힘: 중력이 기기 -x → 이미지 상의 up 은 오른쪽
        assertVec("롤 90°/후면", Vec3(1f, 0f, 0f), gravityUpInWorld(Vec3(-9.81f, 0f, 0f), rot0, false))
        // 전면 카메라는 이미지 좌우가 뒤집히므로 x 부호 반전
        assertVec("롤 90°/전면", Vec3(-1f, 0f, 0f), gravityUpInWorld(Vec3(-9.81f, 0f, 0f), rot0, true))
        // 폰을 바닥에 눕혀 후면 카메라가 아래를 봄: 중력이 기기 -z → up 은 카메라 쪽(+Z_w)
        assertVec("평평히 눕힘/후면", Vec3(0f, 0f, 1f), gravityUpInWorld(Vec3(0f, 0f, -9.81f), rot0, false))
        assertVec("평평히 눕힘/전면", Vec3(0f, 0f, -1f), gravityUpInWorld(Vec3(0f, 0f, -9.81f), rot0, true))
        // 디스플레이가 90° 회전된 상태에서 세로로 세우면 디스플레이 기준 up 은 좌측
        assertVec(
            "디스플레이 90°",
            Vec3(-1f, 0f, 0f),
            gravityUpInWorld(Vec3(0f, -9.81f, 0f), android.view.Surface.ROTATION_90, false),
        )
        assertEquals("영벡터는 null", null, gravityUpInWorld(Vec3(0f, 0f, 0f), rot0, false))
    }

    @Test
    fun tiltDegreesReflectsDeviationFromScreenUp() {
        assertEquals(0f, tiltFromScreenUpDegrees(Vec3(0f, 1f, 0f)), 1e-3f)
        assertEquals(90f, tiltFromScreenUpDegrees(Vec3(1f, 0f, 0f)), 1e-3f)
        assertEquals(45f, tiltFromScreenUpDegrees(Vec3(0f, 1f, 1f)), 1e-3f)
    }

    @Test
    fun aggregatorStatsAreCorrect() {
        val agg = FeatureAggregator()
        agg.add(mapOf("a" to 1f, "b" to 10f))
        agg.add(mapOf("a" to 3f))
        agg.add(mapOf("a" to 5f, "b" to 20f))
        assertEquals(3, agg.frameCount)
        assertEquals(3, agg.count("a"))
        assertEquals(2, agg.count("b"))
        assertEquals(3f, agg.stat("a", "mean")!!, 1e-5f)
        assertEquals(1f, agg.stat("a", "min")!!, 1e-5f)
        assertEquals(5f, agg.stat("a", "max")!!, 1e-5f)
        assertEquals(4f, agg.stat("a", "range")!!, 1e-5f)
        // 모집단 표준편차: sqrt(((1-3)^2+(3-3)^2+(5-3)^2)/3)
        assertEquals(1.63299f, agg.stat("a", "std")!!, 1e-4f)
        agg.reset()
        assertEquals(0, agg.frameCount)
        assertEquals(null, agg.stat("a", "mean"))
    }

    @Test
    fun ruleViolationDirection() {
        fun rule(op: String, threshold: Float) = PostureRule(
            id = "t", exercise = "e", condition = "c", subtype = null, status = RuleStatus.SHIP, reason = null,
            feature = "f__mean", baseFeature = "f", stat = "mean", family = "f", op = op, threshold = threshold,
            view = "C", viewDesc = "", cvAuc = 0.9f, cvBalacc = 0.8f, sampleN = 60, mirrorSafe = true, cautions = emptyList(),
        )
        // op "<" : 값이 임계값보다 작으면 위반
        assertTrue(rule("<", 0.5f).isViolated(0.4f))
        assertTrue(!rule("<", 0.5f).isViolated(0.6f))
        // op ">" : 값이 임계값보다 크면 위반
        assertTrue(rule(">", 0.5f).isViolated(0.6f))
        assertTrue(!rule(">", 0.5f).isViolated(0.4f))
    }
}
