package com.example.trex_kotlin.posture

import kotlin.math.*

/** §39: 측면에서 실제로 보이는 한쪽 관절로 직접 정렬을 잰다. 정상 라벨/초기 자세를 정답으로 고정하지 않는다. */
object PlankGeometry {
    const val HIP = "plank_hip_offset"
    const val HEAD = "plank_head_pitch"
    const val NECK = "plank_neck_pitch"
    const val READY = "plank_side_ready"
    const val SIDE = "plank_visible_side"

    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int): Map<String, Float> {
        if (xy.size != 66 || vis.size != 33 || width <= 0 || height <= 0) return emptyMap()
        fun visible(i: Int, cut: Float = .5f) = vis[i].isFinite() && vis[i] >= cut &&
            xy[i*2].isFinite() && xy[i*2+1].isFinite() && xy[i*2] in 0f..1f && xy[i*2+1] in 0f..1f
        fun p(i: Int) = doubleArrayOf(xy[i*2].toDouble()*width, xy[i*2+1].toDouble()*height)
        fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0]-b[0], a[1]-b[1])
        // 뒤쪽 팔다리의 외삽 좌표를 평균내지 않는다. 머리 가림은 골반 판정을 막지 않는다.
        val sides = listOf(intArrayOf(7,11,23,25,27), intArrayOf(8,12,24,26,28))
        val side = sides.filter { s -> s.drop(1).all { visible(it) } }
            .maxByOrNull { s -> s.drop(1).minOf { vis[it] } } ?: return emptyMap()
        val (earId, shoulderId, hipId, kneeId, ankleId) = side.toList()
        val shoulder = p(shoulderId); val hip = p(hipId); val ankle = p(ankleId); val knee = p(kneeId)
        val torso = dist(shoulder, hip); val length = dist(shoulder, ankle)
        if (torso < 20 || length < torso*1.6 || length < min(width,height)*.25) return emptyMap()
        if (!visible(11,.2f) || !visible(12,.2f)) return emptyMap()
        val ratio = length / max(dist(p(11),p(12)), 1.0)
        // 몸 축 방향 촬영과 서기/무릎 꿇기를 준비 자세로 구분. 이 값들은 정오 임계값이 아닌 관측 조건이다.
        val kneeAngle = FloorFeatureExtractor.ang(hip, knee, ankle)
        val ready = ratio >= 8 && abs(ankle[0]-shoulder[0])/length >= .7 && kneeAngle >= 145
        val result = mutableMapOf(READY to if (ready) 1f else 0f, SIDE to if (shoulderId == 11) 0f else 1f)
        if (!ready) return result
        result[HIP] = FloorFeatureExtractor.devUp(hip, shoulder, ankle).toFloat()
        if (visible(earId) && visible(0)) {
            val ear = p(earId); val nose = p(0)
            val faceLength = dist(ear,nose)
            if (faceLength >= torso*.03 && faceLength <= torso*.65) {
                val ux = (shoulder[0]-hip[0])/torso; val uy = (shoulder[1]-hip[1])/torso
                var nx = -uy; var ny = ux
                if (ny > 0) { nx = -nx; ny = -ny }
                val fx = nose[0]-ear[0]; val fy = nose[1]-ear[1]
                // 얼굴이 몸통에 수직으로 바닥을 향하면 0°. 몸통 앞쪽으로 들면 양수. 화면 좌우 반전에 불변.
                result[HEAD] = Math.toDegrees(atan2(fx*ux+fy*uy, -(fx*nx+fy*ny))).toFloat()
                val ex = ear[0]-shoulder[0]; val ey = ear[1]-shoulder[1]
                if (dist(ear,shoulder) >= torso*.08) result[NECK] = Math.toDegrees(atan2(ex*nx+ey*ny,ex*ux+ey*uy)).toFloat()
            }
        }
        return result
    }
}

data class AlignmentConfig(val lower: Float, val upper: Float, val sustainMs: Long = 1000) {
    init { require(lower.isFinite() && upper.isFinite() && lower < upper && sustainMs >= 500) }
}

data class AlignmentItem(val rule: PostureRule, val value: Float?, val verdict: Verdict, val side: Int = 0, val recovered: Boolean = false) {
    val head get() = rule.baseFeature != PlankGeometry.HIP
    val points get() = if (head) setOf(0,7,8,11,12) else setOf(11,12,23,24,27,28)
    val message get() = when {
        recovered -> if (head) "고개가 몸통 정렬 범위로 돌아왔어요" else "골반이 어깨와 발목 사이의 정렬 범위로 돌아왔어요"
        head && side > 0 -> "고개가 들려 있어요. 시선을 바닥으로 두고 목을 몸통과 나란히 해주세요"
        head -> "고개가 많이 숙여져 있어요. 목을 몸통과 나란히 해주세요"
        side > 0 -> "골반이 올라가 있어요. 어깨와 발목을 잇는 선에 맞춰 조금 내려주세요"
        else -> "골반이 처져 있어요. 어깨와 발목을 잇는 선에 맞춰 조금 올려주세요"
    }
    val detail get() = "${when(rule.baseFeature) { PlankGeometry.HEAD -> "고개 방향"; PlankGeometry.NECK -> "목 기울기"; else -> "골반" }} · " + when (verdict) {
        Verdict.ABSTAIN -> if (value == null) "관측 불가" else "지속 여부 확인 중"
        Verdict.VIOLATION -> "정렬 범위 이탈"
        Verdict.OK -> "관측한 정렬 범위 안"
    } + (value?.let { " (${PostureRule.fmt(it)}${if(head) "°" else ""})" } ?: "")
}

data class AlignmentSnapshot(val items: List<AlignmentItem> = emptyList(), val placementReady: Boolean = false, val visibleSide: Int? = null) {
    val referenceEligible get() = items.size == 3 && items.all { it.verdict == Verdict.OK }
    val issue get() = items.firstOrNull { it.verdict == Verdict.VIOLATION }
    val recovery get() = items.firstOrNull { it.recovered }
}

/** 같은 엔진을 실시간·세트 종료·재생에서 쓴다. 초기 자세를 빼지 않는 절대 정렬 검사이며 전부 beta다. */
class PlankAlignmentTracker(rules: List<PostureRule>) {
    private class State {
        var previous: Long? = null; var side = 0; var since = 0L; var active = 0
        var samples = 0; var ok = 0; var violations = 0; var first: Long? = null; var worst: Float? = null
        fun interrupt() { previous = null; side = 0; active = 0 }
    }
    private val rules = rules.filter { it.kind == "alignment" && it.alignmentConfig != null && it.status != RuleStatus.EXCLUDE }
    private val states = this.rules.associate { it.id to State() }
    private var previousSide: Float? = null
    var snapshot = AlignmentSnapshot(); private set

    @Synchronized fun add(now: Long, features: Map<String, Float>): AlignmentSnapshot {
        val cameraSide = features[PlankGeometry.SIDE]
        if (cameraSide != previousSide) states.values.forEach { it.interrupt() }
        previousSide = cameraSide
        val ready = features[PlankGeometry.READY] == 1f
        snapshot = AlignmentSnapshot(rules.map { rule ->
            val s = states.getValue(rule.id); val c = rule.alignmentConfig!!
            val value = features[rule.baseFeature]?.takeIf { ready && it.isFinite() }
            if (value == null) {
                s.interrupt(); return@map AlignmentItem(rule,null,Verdict.ABSTAIN)
            }
            if (s.previous?.let { now <= it || now-it > 750 } == true) s.interrupt()
            // 복귀 시에는 경계의 80% 안까지 들어와야 한다. 문턱 근처의 흔들림을 복귀로 말하지 않는다.
            val direction = when { value > c.upper -> 1; value < c.lower -> -1
                s.active > 0 && value > c.upper*.8f -> 1
                s.active < 0 && value < c.lower*.8f -> -1; else -> 0 }
            if (s.previous == null || direction != s.side) s.since = now
            s.previous = now; s.side = direction; s.samples++
            if (s.worst == null || abs(value) > abs(s.worst!!)) s.worst = value
            if (now-s.since < c.sustainMs) return@map AlignmentItem(rule,value,Verdict.ABSTAIN,direction)
            val recovered = direction == 0 && s.active != 0
            if (direction != 0) {
                if (s.active != direction) { s.violations++; if(s.first == null) s.first = s.since }
                s.active = direction
            } else { s.ok++; s.active = 0 }
            AlignmentItem(rule,value,if(direction == 0) Verdict.OK else Verdict.VIOLATION,direction,recovered)
        },ready,cameraSide?.toInt())
        return snapshot
    }

    @Synchronized fun results(): List<RuleResult> = rules.map { r ->
        val s = states.getValue(r.id)
        val verdict = when { s.violations > 0 -> Verdict.VIOLATION; s.ok > 0 -> Verdict.OK; else -> Verdict.ABSTAIN }
        RuleResult(r,verdict,s.worst,s.samples,
            abstainReason = if(verdict == Verdict.ABSTAIN) "측면 관측과 지속 정렬 확인 필요" else null,
            measurement = "참고 · ${r.condition}: 지속 이탈 ${s.violations}회" +
                (s.first?.let { " · 첫 이탈 ${it/1000}초" } ?: "") +
                if(verdict == Verdict.ABSTAIN) " · 판정 없음" else " · 초기 자세와 무관한 정렬 기준")
    }
}
