package com.example.trex_kotlin.posture

import java.util.Locale
import kotlin.math.abs

/** 자세의 정오와 분리한 측정 비교. 두 모드에서 같은 값을 계산하고 표시 정책만 달리한다. */
enum class ComparisonPhase(val label: String) { LOW("최소 지점"), HIGH("최대 지점"), RANGE("이동 범위"), HOLD("유지 구간"), WINDOW_RANGE("관측 창 범위") }
enum class ComparisonState { PREPARING, MEASURING, CHANGED, RECOVERED, UNAVAILABLE }

data class ComparisonMetric(val feature: String, val label: String, val unit: String, val noiseFloor: Float) {
    fun format(value: Float): String = if (unit == "°") String.format(Locale.US, "%.1f°", value)
        else String.format(Locale.US, "%.3f", value)
    val landmarks: Set<Int> get() = RuleHighlight.landmarksFor(feature)
}

data class ComparisonValue(
    val metric: ComparisonMetric, val phase: ComparisonPhase, val initial: Float, val current: Float,
    val tolerance: Float, val changed: Boolean, val recovered: Boolean = false,
) {
    val key: String get() = "${metric.feature}|${phase.name}"
    val delta: Float get() = current - initial
    val relativePercent: Float? get() = if (phase == ComparisonPhase.RANGE && abs(initial) > metric.noiseFloor)
        100f * delta / abs(initial) else null
    val detail: String get() = "${metric.label} · ${phase.label}: 처음 ${metric.format(initial)} → 현재 ${metric.format(current)}" +
        (relativePercent?.let { String.format(Locale.US, " (%+.0f%%)", it) } ?: "")
    val observation: String get() = when {
        recovered -> "${metric.label}이 처음 측정 범위로 돌아왔어요"
        phase == ComparisonPhase.RANGE || phase == ComparisonPhase.WINDOW_RANGE -> "처음보다 ${metric.label}의 움직임 범위가 ${if (delta < 0) "작아졌어요" else "커졌어요"}"
        metric.feature.startsWith("hip_dev") || metric.feature == PlankGeometry.HIP -> (if (phase == ComparisonPhase.HOLD) "" else "반복의 같은 지점에서 ") + "처음보다 골반이 ${if (delta > 0) "위" else "아래"}로 이동했어요"
        else -> "${metric.label}이 처음보다 ${if (delta > 0) "커졌어요" else "작아졌어요"}"
    }
}

data class ComparisonSnapshot(
    val state: ComparisonState = ComparisonState.PREPARING, val baselineSamples: Int = 0,
    val values: List<ComparisonValue> = emptyList(), val message: String = "초반 동작을 측정하고 있어요",
    val revision: Long = 0,
) {
    val focus: ComparisonValue? get() = values.firstOrNull { it.changed } ?: values.firstOrNull { it.recovered }
    val landmarks: Set<Int> get() = focus?.metric?.landmarks.orEmpty()
}

/**
 * 기존 앱에서 계산되는 피처만 사용한다. 신호를 몸의 높이로 오인하지 않도록 각도/정규화 단위를 유지한다.
 *
 * 비교는 카운트 신호가 아니라 **비교 신호**(`RepSignal.comparisonSignal()`)를 쓴다 (spec §58). 카운트 신호를 바꿔도
 * (런지: knee_out_mean → knee_mean) 사용자가 이미 쌓은 초기 대비 비교의 단위가 조용히 바뀌지 않게 한다.
 * `comparisonSignal()` 은 멱등이라 이미 비교 신호를 받은 경로에서 다시 불러도 같다.
 */
object ComparisonMetrics {
    fun primary(exercise: String, signal: RepSignal): ComparisonMetric {
        val s = signal.comparisonSignal()
        val label = when (exercise) {
            "푸시업", "니푸쉬업" -> "가슴 이동 범위"
            "힙쓰러스트" -> "골반 이동 범위"
            "크런치" -> "머리 들림 범위(근사)"
            "Y - Exercise" -> "팔 들림 범위(근사)"
            "시저크로스" -> "다리 벌림 범위(투영)"
            else -> "반복 움직임 범위"
        }
        val angle = s.minAmp >= 20f
        return ComparisonMetric(s.feature, label, if (angle) "°" else "정규화 비율", if (angle) 8f else .06f)
    }

    fun forExercise(exercise: String, rules: List<PostureRule>): List<ComparisonMetric> {
        if (exercise == "플랭크") return listOf(ComparisonMetric(PlankGeometry.HIP, "골반 정렬", "정규화 비율", .06f),
            ComparisonMetric(PlankGeometry.HEAD, "고개 기울기", "°", 8f),ComparisonMetric(PlankGeometry.NECK,"목 정렬","°",8f))
        val signal = RepSignals.byExercise[exercise]?.comparisonSignal() ?: return emptyList()
        val extra = if (exercise in FloorTemporal.exercises) listOfNotNull(
            when (exercise) {
                "푸시업" -> ComparisonMetric("hip_dev_ankle", "골반 정렬", "정규화 비율", .06f)
                "니푸쉬업" -> ComparisonMetric("hip_dev_knee", "골반 정렬", "정규화 비율", .06f)
                else -> null
            },
            if (rules.any { it.exercise == exercise && it.status != RuleStatus.EXCLUDE && it.baseFeature == "head_trunk_ang" })
                ComparisonMetric("head_trunk_ang", "투영 고개각", "°", 8f) else null,
        ) else listOf(ComparisonMetric("torso_incl", "몸통 기울기", "°", 8f))
        return listOf(primary(exercise, signal)) + extra
    }
}

data class ComparisonFrame(val timeMs: Long, val features: Map<String, Float>)

/** 동일 반복의 신호 양 끝 15% 구간끼리 비교한다. 방향·단계가 다른 프레임의 평균은 섞지 않는다. */
object PhaseSignature {
    fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
    }

    /** [signal] 은 비교 신호로 바꿔 쓴다 — 카운트 신호를 넘겨도 비교 단위(런지 knee_out_mean)로 반복 양 끝을 가른다 (spec §58). */
    fun compute(frames: List<ComparisonFrame>, signal: RepSignal, metrics: List<ComparisonMetric>): Map<String, Float> {
        if (frames.size < 6) return emptyMap()
        val s = signal.comparisonSignal()
        val usable = frames.filter { it.features[s.feature]?.let { v -> v.isFinite() &&
            (s.plausibleMin == null || v >= s.plausibleMin) && (s.plausibleMax == null || v <= s.plausibleMax) } == true }
        if (usable.size < 6 || usable.size < frames.size * .8) return emptyMap()
        val xs = usable.map { it.features.getValue(s.feature) }
        val lo = xs.min(); val hi = xs.max(); val amp = hi - lo
        if (amp < s.minAmp) return emptyMap()
        val groups = mapOf(ComparisonPhase.LOW to usable.filter { it.features.getValue(s.feature) <= lo + .15f * amp },
            ComparisonPhase.HIGH to usable.filter { it.features.getValue(s.feature) >= hi - .15f * amp })
        return buildMap {
            for (metric in metrics) for ((phase, group) in groups) {
                val values = group.mapNotNull { it.features[metric.feature]?.takeIf(Float::isFinite) }
                if (values.size >= 2 && values.size >= group.size * .8) put("${metric.feature}|${phase.name}", median(values))
            }
            // 양 끝이 모두 관측된 경우만 이동 범위도 남긴다.
            val a = get("${s.feature}|LOW"); val b = get("${s.feature}|HIGH")
            if (a != null && b != null) put("${s.feature}|RANGE", b - a)
        }
    }
}

/**
 * 기준은 항목별로 모으고 고정한다. 정자세 규칙의 통과 여부는 관측 기준의 자격이 아니다.
 * 기본 [signal] 은 등록부의 **비교 신호**다(카운트 신호와 다를 수 있다 — 런지, spec §58).
 */
class PostureComparisonTracker(
    val exercise: String, val metrics: List<ComparisonMetric>, val signal: RepSignal? = RepSignals.byExercise[exercise]?.comparisonSignal(),
    private val baselineCount: Int = 3, private val observationKind: ObservationKind? = null,
    val variantId: String = exercise,
) {
    private val frames = ArrayList<ComparisonFrame>()
    private val initial = HashMap<String, ArrayList<Float>>()
    private val holdFrames = HashMap<String, ArrayList<Pair<Long,Float>>>()
    private val baseline = HashMap<String, Float>()
    private val tolerances = HashMap<String, Float>()
    private val streaks = HashMap<String, Int>()
    private val goodStreaks = HashMap<String, Int>()
    private val active = HashSet<String>()
    private var start: Long? = null
    private var lastTime: Long? = null
    private var lastCompletion: Long? = null
    private var holdSide: Float? = null
    private var revision = 0L
    var referenceEpoch = 0
        private set
    private val history = ArrayList<Pair<Long, List<String>>>()
    private val sideReferences = HashMap<Float, Pair<Map<String,Float>,Map<String,Float>>>()
    var snapshot = ComparisonSnapshot()
        private set
    var latestSignature: Map<String, Float> = emptyMap()
        private set
    private val isHold get() = observationKind == ObservationKind.HOLD || signal?.isometric == true
    private val windowOnly get() = signal == null || observationKind == ObservationKind.WINDOW
    private val sampleCount get() = initial.values.maxOfOrNull { it.size } ?: 0

    /** 재측정은 새 기준 번호를 만들며 이미 남긴 변화 이력은 보존한다. */
    @Synchronized fun reset() {
        frames.clear(); initial.clear(); holdFrames.clear(); baseline.clear(); tolerances.clear()
        streaks.clear(); goodStreaks.clear(); active.clear(); sideReferences.clear()
        start = null; lastTime = null; lastCompletion = null; holdSide = null; latestSignature = emptyMap()
        referenceEpoch++
        snapshot = ComparisonSnapshot(revision = ++revision)
    }

    @Synchronized fun unavailable(message: String = "필요한 관절이 보이면 비교를 이어가요") {
        frames.clear(); holdFrames.clear(); start = null; streaks.clear(); goodStreaks.clear(); active.clear()
        latestSignature = emptyMap()
        initial.keys.filter { it !in baseline }.forEach { initial.remove(it) }
        snapshot = ComparisonSnapshot(ComparisonState.UNAVAILABLE, sampleCount, message = message, revision = ++revision)
    }

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun add(timeMs: Long, features: Map<String, Float>, completedAtMs: Long? = null, anchored: Boolean = true,
                          referenceEligible: Boolean = true): ComparisonSnapshot {
        if (metrics.isEmpty()) return snapshot
        if (lastTime?.let { timeMs <= it || timeMs - it > 1500 } == true) unavailable()
        lastTime = timeMs
        if (!anchored || metrics.none { features[it.feature]?.isFinite() == true }) {
            unavailable(if (!anchored) "운동 시작 구간을 확인하고 있어요" else "필요한 관절이 보이면 비교를 이어가요")
            return snapshot
        }
        if (isHold) {
            val side = features[PlankGeometry.SIDE]
            if (side != null && holdSide != null && side != holdSide) {
                sideReferences[holdSide!!] = baseline.toMap() to tolerances.toMap()
                unavailable("보이는 쪽의 기준을 확인해요")
                baseline.clear(); tolerances.clear(); initial.clear()
                sideReferences[side]?.let { baseline.putAll(it.first); tolerances.putAll(it.second) }
                referenceEpoch++
            }
            if (side != null) holdSide = side
            updateHold(timeMs, features)
            return snapshot
        }
        frames += ComparisonFrame(timeMs, features.toMap())
        if (windowOnly) {
            if (start == null) start = timeMs
            if (timeMs - start!! >= 3000) {
                accept(timeMs, windowSignature())
                frames.clear(); start = timeMs
            }
            return snapshot
        }
        if (frames.size > 128 || start?.let { timeMs - it > 30000 } == true) {
            // 반복을 확정하지 못해도 관측 창의 범위를 제공한다. 이를 반복 단계로 부르지 않는다.
            accept(timeMs, windowSignature()); frames.clear(); start = timeMs
        }
        if (completedAtMs != null && (lastCompletion == null || completedAtMs > lastCompletion!!)) {
            lastCompletion = completedAtMs
            val cycle = frames.filter { it.timeMs > (start ?: Long.MAX_VALUE) && it.timeMs <= completedAtMs }
            val signature = PhaseSignature.compute(cycle, signal!!, metrics)
            frames.removeAll { it.timeMs < completedAtMs }; start = completedAtMs
            if (signature.isEmpty()) {
                latestSignature = emptyMap(); streaks.clear(); goodStreaks.clear(); active.clear()
                snapshot = ComparisonSnapshot(if (baseline.isEmpty()) ComparisonState.PREPARING else ComparisonState.UNAVAILABLE,
                    sampleCount, message = "동작 양 끝이 보이는 반복을 기다리고 있어요", revision = ++revision)
            } else accept(timeMs, signature)
        }
        return snapshot
    }

    private fun windowSignature(): Map<String, Float> = buildMap {
        for (metric in metrics) {
            val xs = frames.mapNotNull { it.features[metric.feature]?.takeIf(Float::isFinite) }
            if (xs.size >= 8 && xs.size >= frames.size*.8)
                put("${metric.feature}|WINDOW_RANGE", xs.max() - xs.min())
        }
    }

    private fun updateHold(timeMs: Long, features: Map<String, Float>) {
        for (metric in metrics) {
            val key = "${metric.feature}|HOLD"
            val xs = holdFrames.getOrPut(key) { arrayListOf() }
            val x = features[metric.feature]?.takeIf(Float::isFinite)
            if (x == null) { xs.clear(); streaks.remove(key); goodStreaks.remove(key); active.remove(key); continue }
            xs += timeMs to x
            if (key !in baseline) {
                if (xs.maxOf { it.second } - xs.minOf { it.second } > 2*metric.noiseFloor) { xs.clear(); xs += timeMs to x }
                if (xs.size >= 8 && timeMs-xs.first().first >= 5000) {
                    baseline[key] = PhaseSignature.median(xs.map { it.second })
                    tolerances[key] = metric.noiseFloor
                    initial[key] = arrayListOf(baseline.getValue(key))
                    xs.clear()
                }
            } else while (xs.isNotEmpty() && timeMs-xs.first().first > 1200) xs.removeAt(0)
            while (xs.size > 128) xs.removeAt(0)
        }
        if (start == null) start = timeMs
        if (baseline.isEmpty()) {
            snapshot = ComparisonSnapshot(message = "처음 자세를 5초간 측정하고 있어요", revision = ++revision)
        } else if (timeMs-start!! >= 1000) {
            latestSignature = holdFrames.mapNotNull { (key,xs) ->
                if (key in baseline && xs.size >= 3 && xs.last().first == timeMs && xs.last().first-xs.first().first >= 500)
                    key to PhaseSignature.median(xs.map { it.second }) else null
            }.toMap()
            if (latestSignature.isNotEmpty()) compare(timeMs,latestSignature)
            else snapshot = ComparisonSnapshot(ComparisonState.MEASURING, sampleCount, message="보이는 부위의 초반 기준을 저장했어요",revision=++revision)
            start = timeMs
        }
    }

    private fun accept(timeMs: Long, signature: Map<String,Float>) {
        latestSignature = signature
        val previousKeys = baseline.keys.toSet()
        for ((key,x) in signature) {
            if (key in baseline) continue
            val xs = initial.getOrPut(key) { arrayListOf() }; xs += x
            if (xs.size > baselineCount) xs.removeAt(0)
            if (xs.size < baselineCount) continue
            val metric = metrics.first { it.feature == key.substringBefore('|') }
            val center = PhaseSignature.median(xs)
            val noise = maxOf(metric.noiseFloor, if (key.endsWith("RANGE")) abs(center)*.15f else 0f)
            if (xs.max()-xs.min() > 2*noise) continue
            baseline[key] = center
            tolerances[key] = maxOf(noise,3*PhaseSignature.median(xs.map { abs(it-center) }))
        }
        if (previousKeys.any { it in signature }) compare(timeMs, signature.filterKeys { it in previousKeys })
        else snapshot = ComparisonSnapshot(if (baseline.isEmpty()) ComparisonState.PREPARING else ComparisonState.MEASURING,
            sampleCount, message = if (baseline.isEmpty()) "초반 움직임을 비교 기준으로 저장하고 있어요 ($sampleCount/$baselineCount)"
                else "보이는 부위의 초반 기준을 저장했어요", revision = ++revision)
    }

    private fun compare(timeMs: Long, signature: Map<String, Float>) {
        val base = baseline
        val result = ArrayList<ComparisonValue>()
        for ((key, before) in base) {
            val current = signature[key]
            if (current == null) { streaks.remove(key); goodStreaks.remove(key); active.remove(key); continue }
            val metric = metrics.first { it.feature == key.substringBefore('|') }
            val phase = ComparisonPhase.valueOf(key.substringAfter('|'))
            val tolerance = tolerances.getValue(key)
            val d = current - before
            val side = if (d > tolerance) 1 else if (d < -tolerance) -1 else 0
            val old = streaks[key] ?: 0
            streaks[key] = if (side == 0) 0 else if (old * side > 0) old + side else side
            val changed = abs(streaks.getValue(key)) >= 2
            goodStreaks[key] = if (side == 0) (goodStreaks[key] ?: 0) + 1 else 0
            val recovered = side == 0 && goodStreaks.getValue(key) >= 2 && key in active
            if (changed) active += key else if (recovered) active -= key
            result += ComparisonValue(metric, phase, before, current, tolerance, changed, recovered)
        }
        val ordered = result.sortedBy { when { it.changed && it.phase == ComparisonPhase.RANGE -> 0; it.changed -> 1; it.recovered -> 2; else -> 3 } }
        val state = when { ordered.isEmpty() -> ComparisonState.UNAVAILABLE
            ordered.any { it.changed } -> ComparisonState.CHANGED
            ordered.any { it.recovered } -> ComparisonState.RECOVERED
            else -> ComparisonState.MEASURING }
        val focus = ordered.firstOrNull { it.changed || it.recovered }
        snapshot = ComparisonSnapshot(state, sampleCount, ordered,
            focus?.observation ?: if (ordered.isEmpty()) "같은 지점의 관절이 보이면 비교를 이어가요" else "관측한 움직임을 처음과 비교하고 있어요", ++revision)
        if (focus != null && history.lastOrNull()?.second?.singleOrNull()?.contains(focus.observation) != true) history += timeMs to listOf("기준 $referenceEpoch · $variantId · 처음 대비 변화 · ${focus.observation} · ${focus.detail}")
    }

    @Synchronized fun report(endAtMs: Long, originMs: Long = 0): List<String> = history.filter { it.first <= endAtMs }
        .takeLast(3).map { (t, lines) -> "${((t-originMs)/1000).coerceAtLeast(0)}초 · ${lines.single()}" }
}

/** 음성은 같은 스냅샷의 지속 변화·복귀만 알린다. 음소거 중에는 발화 이력을 소비하지 않는다. */
class ComparisonSpeech {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastRevision = -1L
    private val spoken = HashSet<String>()
    private val spokenAt = HashMap<String,Long>()
    @Synchronized fun clear() { spoken.clear(); spokenAt.clear(); lastRevision = -1; lastAt = Long.MIN_VALUE / 2 }
    @Synchronized fun next(now: Long, snapshot: ComparisonSnapshot, enabled: Boolean): String? {
        if (!enabled || snapshot.revision == lastRevision || now - lastAt < 8000) return null
        val item = snapshot.focus ?: return null
        if (item.recovered && item.key !in spoken) return null
        if (item.changed && spokenAt[item.key]?.let { now-it < 20000 } == true) return null
        lastAt = now; lastRevision = snapshot.revision
        if (item.changed) { spoken += item.key; spokenAt[item.key] = now } else spoken -= item.key
        return item.observation
    }
}
