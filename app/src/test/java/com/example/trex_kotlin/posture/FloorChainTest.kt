package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 바닥 계열 관측 층(§99, 설계 §4.1) — 롤 불변·up 뒤집힘 무해·좌우 반전·쪽 잠금 이력·측면도·플랭크 파리티를 잠근다.
 * 몸은 합성 좌표(이미지 1000×1000, 머리 왼쪽, 가까운 쪽 = MediaPipe 왼쪽 관절). 재생기(replay-jvm)도 이 파일을 컴파일한다 — 엔진 파일 밖은 쓰지 않는다.
 */
class FloorChainTest {
    private val screenUp = Vec3(0f, 1f, 0f)

    /** 정규화 좌표 33점 — [pts] 는 (인덱스 → x, y). 가까운 쪽(왼쪽) 0.95, 먼 쪽 [far], 얼굴(0) 0.95, 나머지 0. */
    private fun body(pts: Map<Int, Pair<Float, Float>>, far: Float = 0.4f): Pair<FloatArray, FloatArray> {
        val xy = FloatArray(66) { 0.5f }
        val vis = FloatArray(33)
        for ((i, p) in pts) { xy[i * 2] = p.first; xy[i * 2 + 1] = p.second; vis[i] = if (i == 0 || i % 2 == 1) 0.95f else far }
        return xy to vis
    }
    /** 먼 쪽(오른쪽) 관절 = 가까운 쪽 + 화면 세로 0.005(카메라 높이 성분만 — 측면). */
    private fun withFar(near: Map<Int, Pair<Float, Float>>): Map<Int, Pair<Float, Float>> =
        near + near.filterKeys { it in setOf(7, 11, 13, 15, 23, 25, 27) }.map { (i, p) -> i + 1 to (p.first to p.second + 0.005f) }

    // 크런치: 무릎 세움, 어깨를 골반 기준 20° 들어 올림
    private fun crunch(lift: Double = 20.0): Map<Int, Pair<Float, Float>> {
        val hip = 0.50f to 0.62f
        fun up(dx: Float, dy: Float): Pair<Float, Float> {   // 골반 기준 (dx, dy) 를 lift 만큼 머리 쪽으로 들어 올림(화면 위 = −y)
            val a = Math.toRadians(lift); val x = dx * cos(a) - dy * sin(a); val y = dx * sin(a) + dy * cos(a)
            return (hip.first + x.toFloat()) to (hip.second + y.toFloat())
        }
        return withFar(mapOf(0 to up(-0.35f, -0.035f), 7 to up(-0.32f, -0.02f), 11 to up(-0.25f, 0f), 13 to (0.32f to 0.64f), 15 to (0.38f to 0.60f),
            23 to hip, 25 to (0.62f to 0.50f), 27 to (0.74f to 0.62f)))
    }
    // 레그 레이즈: 몸통 수평, 다리를 60° 들어 올림
    private fun legRaise(): Map<Int, Pair<Float, Float>> {
        val a = Math.toRadians(60.0)
        return withFar(mapOf(0 to (0.15f to 0.585f), 7 to (0.18f to 0.60f), 11 to (0.25f to 0.62f), 13 to (0.32f to 0.64f), 15 to (0.38f to 0.62f),
            23 to (0.50f to 0.62f), 25 to ((0.50 + 0.12 * cos(a)).toFloat() to (0.62 - 0.12 * sin(a)).toFloat()),
            27 to ((0.50 + 0.24 * cos(a)).toFloat() to (0.62 - 0.24 * sin(a)).toFloat())))
    }
    // 플랭크: 엎드려 전완 지지, 얼굴은 바닥
    private fun plank(hipY: Float = 0.53f): Map<Int, Pair<Float, Float>> = withFar(mapOf(0 to (0.17f to 0.545f), 7 to (0.18f to 0.52f), 11 to (0.25f to 0.52f),
        13 to (0.26f to 0.60f), 15 to (0.36f to 0.60f), 23 to (0.50f to hipY), 25 to (0.66f to 0.545f), 27 to (0.80f to 0.56f)))

    /** 이미지를 중심 기준 [deg] 만큼 돌린다(폰 롤) — up 도 같이 돈다. */
    private fun rotate(pts: Map<Int, Pair<Float, Float>>, deg: Double): Map<Int, Pair<Float, Float>> {
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        return pts.mapValues { (_, p) -> val x = p.first - 0.5; val y = p.second - 0.5; (0.5 + x * c - y * s).toFloat() to (0.5 + x * s + y * c).toFloat() }
    }
    private fun upFor(deg: Double): Vec3 { val a = Math.toRadians(deg); return Vec3(sin(a).toFloat(), cos(a).toFloat(), 0f) }

    private fun feats(kind: FloorChain.Kind, pts: Map<Int, Pair<Float, Float>>, up: Vec3?, far: Float = 0.4f): Map<String, Float> {
        val (xy, vis) = body(pts, far)
        return FloorChain(kind).features(0L, xy, vis, 1000, 1000, up)
    }

    private val angleKeys = setOf(FloorChain.AXIS_H, FloorChain.KNEE, FloorChain.ELBOW, FloorChain.TRUNK_LIFT, FloorChain.EAR_LIFT, FloorChain.THIGH, FloorChain.LEG,
        FloorChain.HEAD_LIFT, FloorChain.TORSO_ELEV, FloorChain.THIGH_FAR, FloorChain.HEAD_PITCH, FloorChain.FACE)
    private fun assertSame(msg: String, a: Map<String, Float>, b: Map<String, Float>) {
        assertEquals(msg, a.keys, b.keys)
        for ((k, v) in a) {
            val tol = when (k) { in angleKeys -> 0.1f; FloorChain.TORSO -> 0.05f; FloorChain.RATIO -> 0.05f; else -> 1e-3f }
            assertEquals("$msg/$k", v, b.getValue(k), tol)
        }
    }

    @Test fun crunchFeaturesReadTheLiftAgainstTheAnkleHipLine() {
        val f = feats(FloorChain.Kind.CRUNCH, crunch(20.0), screenUp)
        assertEquals(0f, f.getValue(FloorChain.SIDE), 0f); assertEquals(1f, f.getValue(FloorChain.CHAIN), 0f); assertEquals(1f, f.getValue(FloorChain.UP_OK), 0f)
        assertEquals(20f, f.getValue(FloorChain.TRUNK_LIFT), 0.2f)
        assertEquals(20f, f.getValue(FloorChain.AXIS_H), 0.2f); assertEquals(20f, f.getValue(FloorChain.TORSO_ELEV), 0.2f)
        assertEquals(0f, feats(FloorChain.Kind.CRUNCH, crunch(0.0), screenUp).getValue(FloorChain.TRUNK_LIFT), 0.2f)
        assertTrue(f.getValue(FloorChain.YAW) <= 0.15f)
        assertFalse("크런치에는 레그 레이즈·플랭크 피처가 없다", f.containsKey(FloorChain.THIGH) || f.containsKey(FloorChain.HIP_OFF))
        // 얼굴 방향(§100): 보이는 쪽 귀 꼭짓점의 코–귀–골반 2D 각 — 크런치에만
        val pts = crunch(20.0)
        fun px(i: Int) = doubleArrayOf(pts.getValue(i).first * 1000.0, pts.getValue(i).second * 1000.0)
        assertEquals(Floor2d.ang(px(0), px(7), px(23)).toFloat(), f.getValue(FloorChain.FACE), 0.5f)
        assertFalse(feats(FloorChain.Kind.LEG_RAISE, legRaise(), screenUp).containsKey(FloorChain.FACE))
        assertFalse(feats(FloorChain.Kind.PLANK, plank(), screenUp).containsKey(FloorChain.FACE))
    }

    @Test fun legRaiseThighAndLegAreElevationsFromTheTrunkExtension() {
        val f = feats(FloorChain.Kind.LEG_RAISE, legRaise(), screenUp)
        assertEquals(60f, f.getValue(FloorChain.THIGH), 0.2f); assertEquals(60f, f.getValue(FloorChain.LEG), 0.2f)
        assertEquals(0f, f.getValue(FloorChain.TORSO_ELEV), 0.5f); assertEquals(180f, f.getValue(FloorChain.KNEE), 0.5f)
        assertTrue(f.containsKey(FloorChain.HEAD_LIFT)); assertTrue(f.containsKey(FloorChain.KNEE_GAP)); assertTrue(f.containsKey(FloorChain.THIGH_FAR))
        assertEquals(60f, f.getValue(FloorChain.THIGH_FAR), 1f)
    }

    @Test fun plankFeaturesMatchLegacyDefinitions() {
        val f = feats(FloorChain.Kind.PLANK, plank(), screenUp)
        val (xy, vis) = body(plank())
        val legacy = PlankGeometry.features(xy, vis, 1000, 1000)
        assertEquals(1f, legacy.getValue(PlankGeometry.READY), 0f)
        assertEquals(legacy.getValue(PlankGeometry.HIP), f.getValue(FloorChain.HIP_OFF), 1e-4f)
        assertEquals(legacy.getValue(PlankGeometry.HEAD), f.getValue(FloorChain.HEAD_PITCH), 1e-3f)
        assertTrue(f.getValue(FloorChain.HIP_FLOOR) > 0.10f)
        assertTrue(f.getValue(FloorChain.KNEE) > 170f)
        assertTrue(f.getValue(FloorChain.AXIS_H) < 10f)
        // 골반을 지지선까지 내리면(엎드려 쉼) 지지선 대비 높이가 0 근처
        assertTrue(feats(FloorChain.Kind.PLANK, plank(hipY = 0.585f), screenUp).getValue(FloorChain.HIP_FLOOR) < 0.10f)
    }

    @Test fun featuresAreInvariantToPhoneRoll() {
        for ((kind, pts) in listOf(FloorChain.Kind.CRUNCH to crunch(20.0), FloorChain.Kind.LEG_RAISE to legRaise(), FloorChain.Kind.PLANK to plank())) {
            val ref = feats(kind, pts, upFor(0.0))
            for (deg in listOf(90.0, 180.0, 37.0, -90.0)) assertSame("$kind/$deg", ref, feats(kind, rotate(pts, deg), upFor(deg)))
        }
    }

    @Test fun upFlipFromSanityCheckIsHarmless() {
        // 앱 checkUpSanity 가 레그 레이즈 상단에서 up 을 뒤집어도(골반이 발목 아래) 원래 중력으로 되돌려 같은 값
        val up = upFor(90.0); val pts = rotate(legRaise(), 90.0)
        val flipped = up * -1f
        assertSame("flip", feats(FloorChain.Kind.LEG_RAISE, pts, up), feats(FloorChain.Kind.LEG_RAISE, pts, FloorChain.gravityUp(flipped, fromGravity = true, flipped = true)))
        // 수평 대비 축각은 부호 없는 축이라 뒤집힌 up 에서도 같다
        assertEquals(feats(FloorChain.Kind.LEG_RAISE, pts, up).getValue(FloorChain.AXIS_H), feats(FloorChain.Kind.LEG_RAISE, pts, flipped).getValue(FloorChain.AXIS_H), 1e-3f)
        assertNull(FloorChain.gravityUp(up, fromGravity = false, flipped = false))
    }

    @Test fun unreliableUpWithholdsOnlyTheAbsoluteHorizontal() {
        val flat = feats(FloorChain.Kind.CRUNCH, crunch(20.0), Vec3(0.05f, 0.05f, 1f))
        val none = feats(FloorChain.Kind.CRUNCH, crunch(20.0), null)
        val ok = feats(FloorChain.Kind.CRUNCH, crunch(20.0), screenUp)
        for (f in listOf(flat, none)) {
            assertEquals(0f, f.getValue(FloorChain.UP_OK), 0f)
            assertFalse("누운 영역 게이트(절대 수평)는 유보", f.containsKey(FloorChain.AXIS_H))
            // 몸 내재 각과 사이클 신호는 그대로(부호는 화면 위 기준)
            assertEquals(ok.getValue(FloorChain.TRUNK_LIFT), f.getValue(FloorChain.TRUNK_LIFT), 1e-4f)
            assertEquals(ok.getValue(FloorChain.KNEE), f.getValue(FloorChain.KNEE), 1e-4f)
            // 상체 기울기는 화면 위 기준으로 낸다 — 추적기가 누운 기준 대비로만 쓴다(리뷰 2026-10-07: 유보하면 레그 레이즈 출구·상체 들기가 꺼졌다)
            assertEquals(ok.getValue(FloorChain.TORSO_ELEV), f.getValue(FloorChain.TORSO_ELEV), 1e-3f)
        }
        val leg = feats(FloorChain.Kind.LEG_RAISE, legRaise(), null)
        assertFalse(leg.containsKey(FloorChain.AXIS_H)); assertEquals(0f, leg.getValue(FloorChain.TORSO_ELEV), 0.5f)
    }

    @Test fun plankWithoutGravityFallsBackToScreenFlatnessForProne() {
        // up 이 없으면 엎드림을 화면 수평도로 묻는다 — 서 있는(화면 세로) 몸은 플랭크 시간이 아니다(전에는 묻지 않았다)
        val lying = feats(FloorChain.Kind.PLANK, plank(), null)
        assertFalse(lying.containsKey(FloorChain.AXIS_H)); assertTrue(lying.getValue(FloorChain.FLAT_SCREEN) > 0.95f)
        assertNull(PlankHoldClock.stopReason(lying))
        val standing = feats(FloorChain.Kind.PLANK, rotate(plank(), 90.0), null)
        assertTrue(standing.getValue(FloorChain.FLAT_SCREEN) < 0.3f)
        assertEquals(PlankHoldClock.NOT_PRONE, PlankHoldClock.stopReason(standing))
        assertFalse("중력이 있으면 축각만", feats(FloorChain.Kind.PLANK, plank(), screenUp).containsKey(FloorChain.FLAT_SCREEN))
    }

    @Test fun extractorAttachesChainFeaturesOnlyWhenTheTorsoIsObserved() {
        // 관절 11개짜리 프레임(어깨·골반 안 보임)에서 fc_side·fc_up_ok·fc_chain = 0 만 붙어 피처 맵이 늘 비지 않던 것 — '검출 = 측정 가능' 함정(§31a)
        val (xy, _) = body(crunch(0.0))
        val faint = FloatArray(33) { 0.1f }
        for (ex in listOf("크런치", "라잉 레그 레이즈", "플랭크")) {
            val f = FloorFeatureExtractor().computeForExercise(ex, xy, faint, 1000, 1000, screenUp, 0L)
            assertTrue("$ex $f", f.keys.none { it.startsWith("fc_") })
            assertTrue("$ex — 측정 가능하지 않다", f.isEmpty())
        }
        val (xy2, vis2) = body(crunch(20.0))
        assertTrue(FloorFeatureExtractor().computeForExercise("크런치", xy2, vis2, 1000, 1000, screenUp, 0L).containsKey(FloorChain.TRUNK_LIFT))
    }

    @Test fun mirroringTheBodyKeepsEveryFeature() {
        for ((kind, pts) in listOf(FloorChain.Kind.CRUNCH to crunch(20.0), FloorChain.Kind.LEG_RAISE to legRaise(), FloorChain.Kind.PLANK to plank())) {
            val mirrored = pts.mapValues { (_, p) -> (1f - p.first) to p.second }
            assertSame("$kind", feats(kind, pts, screenUp), feats(kind, mirrored, screenUp))
        }
    }

    @Test fun sideLockHoldsThroughBriefFlickerAndSwitchesAfterTwoSeconds() {
        val c = FloorChain(FloorChain.Kind.CRUNCH)
        val (xy, visLeft) = body(crunch(0.0))
        val visRight = FloatArray(33) { i -> if (i == 0) 0.95f else if (i in setOf(8, 12, 14, 16, 24, 26, 28)) 0.95f else if (i in setOf(7, 11, 13, 15, 23, 25, 27)) 0.4f else 0f }
        var t = 0L
        fun step(v: FloatArray) = c.features(t, xy, v, 1000, 1000, screenUp).getValue(FloorChain.SIDE).also { t += 300 }
        repeat(6) { step(visLeft) }                          // 0~1500 ms — 왼쪽으로 잠금
        assertEquals(0, c.lockedSide)
        // 깜빡임(번갈아) — 바꾸지 않는다
        repeat(10) { k -> assertEquals(0f, step(if (k % 2 == 0) visRight else visLeft), 0f) }
        assertEquals(0, c.switches)
        // 반대쪽이 2 s 넘게 더 잘 보이면 바꾼다
        val sides = (0 until 9).map { step(visRight) }
        assertEquals(0f, sides[5], 0f)                       // 1.5 s — 아직
        assertEquals(1f, sides.last(), 0f)
        assertEquals(1, c.lockedSide); assertEquals(1, c.switches)
        c.reset(); assertNull(c.lockedSide); assertEquals(0, c.switches)
    }

    @Test fun yawReadsAlongTorsoSeparationAndIgnoresCameraHeight() {
        val side = feats(FloorChain.Kind.CRUNCH, crunch(0.0), screenUp).getValue(FloorChain.YAW)
        assertTrue("측면 $side", side <= 0.05f)
        // 카메라가 높아 먼 쪽이 화면 아래로 0.4 몸통 내려감(수직 성분) — 측면 그대로
        val high = crunch(0.0).toMutableMap().apply { this[12] = 0.25f to 0.72f; this[24] = 0.50f to 0.72f }
        assertTrue(feats(FloorChain.Kind.CRUNCH, high, screenUp).getValue(FloorChain.YAW) <= 0.05f)
        // 머리 쪽으로 돌아 찍음 — 먼 어깨·골반이 몸통 축 방향으로 0.3 몸통 벌어짐
        val turned = crunch(0.0).toMutableMap().apply { this[12] = 0.325f to 0.625f; this[24] = 0.575f to 0.625f }
        assertEquals(0.3f, feats(FloorChain.Kind.CRUNCH, turned, screenUp).getValue(FloorChain.YAW), 0.01f)
        // 먼 쪽이 거의 안 보이면 측면도를 내지 않는다(판별은 유보)
        assertFalse(feats(FloorChain.Kind.CRUNCH, crunch(0.0), screenUp, far = 0.1f).containsKey(FloorChain.YAW))
    }

    @Test fun missingChainJointsWithholdOnlyTheirFeatures() {
        // 레그 레이즈 상단에서 발목이 화면 위로 잘려도 허벅지는 남는다
        val cut = legRaise().toMutableMap().apply { this[27] = 0.62f to -0.05f; this[28] = 0.62f to -0.045f }
        val f = feats(FloorChain.Kind.LEG_RAISE, cut, screenUp)
        assertEquals(60f, f.getValue(FloorChain.THIGH), 0.2f)
        assertFalse(f.containsKey(FloorChain.LEG)); assertFalse(f.containsKey(FloorChain.KNEE)); assertEquals(0f, f.getValue(FloorChain.CHAIN), 0f)
        // 어깨·골반이 없으면 사슬도 피처도 없다
        val noTorso = crunch(0.0).toMutableMap().apply { remove(11); remove(12) }
        val g = feats(FloorChain.Kind.CRUNCH, noTorso, screenUp)
        assertEquals(0f, g.getValue(FloorChain.CHAIN), 0f); assertFalse(g.containsKey(FloorChain.TRUNK_LIFT))
    }

    /** `plank_replay_fixture.tsv`(AIHub 4클립×5뷰 원좌표) — 화면이 서 있으면 `fc_hip_off` = `plank_hip_offset`, `fc_head_pitch` = `plank_head_pitch`(|값| ≤ 90). */
    @Test fun plankHipOffsetParityOnReplayFixture() {
        var chain = FloorChain(FloorChain.Kind.PLANK)
        var hip = 0; var head = 0; var frames = 0; var legacyHip = 0; var sideDiff = 0
        javaClass.getResourceAsStream("/plank_replay_fixture.tsv")!!.bufferedReader().useLines { lines -> lines.forEach { line ->
            val c = line.split('\t')
            if (c[0] == "CASE") { chain = FloorChain(FloorChain.Kind.PLANK); return@forEach }
            val ms = c[1].toLong(); val w = c[2].toInt(); val h = c[3].toInt()
            val xy = c[4].split(',').map { it.toFloat() }.toFloatArray(); val vis = c[5].split(',').map { it.toFloat() }.toFloatArray()
            val legacy = PlankGeometry.features(xy, vis, w, h)
            val f = chain.features(ms, xy, vis, w, h, screenUp)
            frames++
            if (legacy.containsKey(PlankGeometry.HIP)) legacyHip++
            if (f[FloorChain.SIDE] != legacy[PlankGeometry.SIDE]) { if (legacy.containsKey(PlankGeometry.HIP)) sideDiff++; return@forEach }
            legacy[PlankGeometry.HIP]?.let { v -> assertEquals("$ms hip", v, f.getValue(FloorChain.HIP_OFF), 1e-3f); hip++ }
            legacy[PlankGeometry.HEAD]?.takeIf { abs(it) <= 90f }?.let { v -> f[FloorChain.HEAD_PITCH]?.let { assertEquals("$ms head", v, it, 1e-2f); head++ } }
        } }
        assertEquals(320, frames)
        // 픽스처의 준비 통과(plank_side_ready) 프레임 전부를 같은 쪽으로 비교한다 — 쪽 잠금이 프레임별 최선 쪽과 어긋난 프레임 0(2026-10-07: 77/77)
        assertEquals("쪽 불일치 $sideDiff", 0, sideDiff)
        assertEquals(legacyHip, hip); assertTrue("골반 파리티 비교 프레임 $hip", hip >= 70)
        assertTrue("고개 파리티 비교 프레임 $head", head >= 60)
    }
}
