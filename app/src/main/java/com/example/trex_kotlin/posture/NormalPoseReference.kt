package com.example.trex_kotlin.posture

/** 실제 Android VIDEO 추론의 정상 라벨 클립에서 얻은 단계별 표본 범위. 출시 임계값이 아니다. */
data class NormalPoseBand(
    val exercise: String, val view: String, val feature: String, val phase: ComparisonPhase,
    val lower: Float, val upper: Float, val clips: Int, val people: Int,
)

data class NormalPoseMatch(val band: NormalPoseBand, val metric: ComparisonMetric, val current: Float) {
    val outside: Boolean get() = current < band.lower || current > band.upper
    val detail: String get() = "${metric.label} · ${band.phase.label}: 현재 ${metric.format(current)} / " +
        "정상 표본 ${metric.format(band.lower)}~${metric.format(band.upper)} (${band.clips}클립·${band.people}명)"
}

class NormalPoseReference(val bands: List<NormalPoseBand>) {
    fun compare(exercise: String, view: String?, signature: Map<String, Float>, metrics: List<ComparisonMetric>): List<NormalPoseMatch> {
        if (view == null) return emptyList() // 촬영 방향을 추측해 다른 방향의 정상값을 적용하지 않는다.
        return bands.filter { it.exercise == exercise && it.view == view }.mapNotNull { band ->
            val metric = metrics.firstOrNull { it.feature == band.feature } ?: return@mapNotNull null
            val value = signature["${band.feature}|${band.phase.name}"]?.takeIf(Float::isFinite) ?: return@mapNotNull null
            NormalPoseMatch(band, metric, value)
        }.sortedByDescending { it.outside }
    }

    companion object {
        const val ASSET = "posture/normal_pose_reference.tsv"
        /** 순수 파서여서 앱·단위 테스트가 동일한 데이터 검사를 사용한다. */
        fun parse(lines: Sequence<String>): NormalPoseReference = NormalPoseReference(lines.filter { it.isNotBlank() && !it.startsWith('#') }.mapNotNull { line ->
            runCatching {
                val c = line.split('\t'); require(c.size == 9)
                val low = c[4].toFloat(); val high = c[5].toFloat(); val n = c[6].toInt(); val people = c[7].toInt()
                require(low.isFinite() && high.isFinite() && low <= high && n >= 2 && people >= 1 && c[8] == "android_video_training")
                NormalPoseBand(c[0], c[1], c[2], ComparisonPhase.valueOf(c[3]), low, high, n, people)
            }.getOrNull()
        }.toList())
    }
}
