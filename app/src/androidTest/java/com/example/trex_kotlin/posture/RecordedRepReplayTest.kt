package com.example.trex_kotlin.posture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 과거 기기 피처를 현재 반복 엔진으로 재생한다. 원래 시각·순서·결측을 보존하며 카메라/추론은 실행하지 않는다.
 * 실행 전 평가 앱 external files/rep_replay/input.jsonl을 준비한다. 이 검사 자체는 횟수 정답을 만들지 않는다.
 * 입력이 없거나 파싱할 수 없으면 실패한다. 지원하지 않는 종목/방식은 성공 0회 대신 명시적 skip으로 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class RecordedRepReplayTest {
    @Test(timeout = 60_000)
    fun replayRecordedFeaturesWithoutRetimingOrImputation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".replay")) { "평가 전용 패키지에서만 실행하세요" }
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "rep_replay")
        val input = File(dir, "input.jsonl")
        check(input.isFile && input.length() > 0L) { "rep_replay/input.jsonl 입력 파일이 필요합니다" }
        val bytes = input.readBytes()
        val inputHash = sha256(bytes)
        // 모든 입력의 형식을 먼저 검사한다. 일부 파싱 실패를 빈 프레임/지원하지 않는 종목으로 바꾸지 않는다.
        val sets = bytes.toString(Charsets.UTF_8).lineSequence().withIndex().filter { it.value.isNotBlank() }
            .map { (index, line) -> parseSet(JSONObject(line), index + 1, sha256(line.toByteArray(Charsets.UTF_8))) }.toList()
        check(sets.isNotEmpty()) { "재생할 세트가 하나도 없습니다" }
        val standing = PostureRuleSet.load(context)
        val floor = PostureRuleSet.load(context, FLOOR_RULES_ASSET)
        val rules = PostureRuleSet("${standing.version}+${floor.version}", standing.generated, standing.rules + floor.rules)
        val rulesHashes = JSONObject().put(PostureRuleSet.ASSET_PATH,
            context.assets.open(PostureRuleSet.ASSET_PATH).use { sha256(it.readBytes()) })
            .put(FLOOR_RULES_ASSET, context.assets.open(FLOOR_RULES_ASSET).use { sha256(it.readBytes()) })
        // 이 평가가 소유한 결과 한 파일만 갱신한다. 일반 앱 로그·설정·입력은 수정하지 않는다.
        File(dir, "results.jsonl").bufferedWriter(Charsets.UTF_8).use { output ->
            sets.forEach { set ->
                val result = replay(set, rules).put("input_sha256", inputHash)
                    .put("input_line_sha256", set.lineHash).put("input_line", set.line)
                    .put("package", context.packageName).put("rules_version", rules.version)
                    .put("rules_assets_sha256", rulesHashes)
                output.append(result.toString()).append('\n')
            }
        }
    }

    private data class Frame(val json: JSONObject, val timeMs: Long, val features: Map<String, Float>)
    private data class InputSet(val id: String, val exercise: String, val pattern: String?, val patternSource: String,
        val frames: List<Frame>, val boundaries: List<Long>, val boundarySource: String,
        val line: Int, val lineHash: String)

    private fun parseSet(json: JSONObject, line: Int, lineHash: String): InputSet {
        val id = json.getString("set_id").also { check(it.isNotBlank()) { "$line 줄 set_id가 비었습니다" } }
        val exercise = json.getString("exercise").also { check(it.isNotBlank()) { "$id 운동명이 비었습니다" } }
        val framesJson = json.getJSONArray("frames")
        check(framesJson.length() > 0) { "$id 프레임 배열이 비었습니다" }
        val frames = (0 until framesJson.length()).map { index ->
            val frame = framesJson.getJSONObject(index)
            val timeMs = integerMs(frame.get("t_ms"), "$id/$index t_ms")
            val featuresJson = if (frame.isNull("features")) null else frame.getJSONObject("features")
            val features = linkedMapOf<String, Float>()
            featuresJson?.keys()?.forEach { key ->
                val value = featuresJson.get(key)
                features[key] = when (value) {
                    JSONObject.NULL -> Float.NaN // null을 값 0이나 직전 관측으로 대체하지 않는다.
                    is Number -> value.toFloat()
                    else -> error("$id/$index 피처 $key 값이 숫자/null이 아닙니다")
                }
            }
            Frame(frame, timeMs, features)
        }
        val nestedPattern = json.optJSONObject("reps")?.takeUnless { it.isNull("pattern") }?.getString("pattern")
        val pattern = if (!json.isNull("pattern")) json.getString("pattern") else nestedPattern
        val patternSource = when { !json.isNull("pattern") -> "input.pattern"; nestedPattern != null -> "input.reps.pattern"; else -> "profile_default" }
        val explicitBoundaries = if (json.isNull("context_boundaries_ms")) null else json.getJSONArray("context_boundaries_ms")
        val noteBoundary = Regex("context_boundaries_ms=\\[([^]]*)]").find(json.optString("note", ""))
        val boundaries = if (explicitBoundaries != null) (0 until explicitBoundaries.length()).map {
            integerMs(explicitBoundaries.get(it), "$id context_boundaries_ms[$it]")
        } else noteBoundary?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.split(',')?.map {
            it.trim().toLongOrNull() ?: error("$id note의 촬영 경계 시각을 읽을 수 없습니다")
        }.orEmpty()
        return InputSet(id, exercise, pattern, patternSource, frames, boundaries,
            when { explicitBoundaries != null -> "input.context_boundaries_ms"; noteBoundary != null -> "input.note"; else -> "not_recorded" }, line, lineHash)
    }

    private fun replay(set: InputSet, rules: PostureRuleSet): JSONObject {
        val originalProfile = ExerciseRepProfiles.forExercise(set.exercise)
        val pattern = set.pattern?.let { value -> RepMovementPattern.entries.find { it.name == value } }
            ?: originalProfile?.takeUnless { it.isometric }?.defaultPattern?.takeIf { set.pattern == null }
        val reason = when {
            originalProfile == null -> "exercise_profile_not_supported"
            originalProfile.isometric -> "hold_exercise_no_rep_counter"
            pattern == null -> "movement_pattern_not_recognized"
            pattern !in originalProfile.allowedPatterns -> "movement_pattern_not_supported"
            else -> null
        }
        val result = JSONObject().put("schema", "trex-recorded-rep-replay/1")
            .put("set_id", set.id).put("exercise", set.exercise)
            .put("canonical_exercise", originalProfile?.exercise ?: JSONObject.NULL)
            .put("pattern", pattern?.name ?: set.pattern ?: JSONObject.NULL).put("pattern_source", set.patternSource)
            .put("feature_only_replay", true).put("camera_accuracy_verified", false).put("inference_accuracy_verified", false)
            .put("rep_accuracy_verified", false).put("form_accuracy_verified", false)
            .put("timestamp_policy", "original t_ms and frame order; no interpolation, filtering, sorting or resampling")
            .put("view_warmup_policy", "no invented preparation frames; recent view starts empty")
            .put("floor_feature_policy", "stored features reused without image/3D recomputation")
            .put("frame_count", set.frames.size).put("context_boundaries_ms", JSONArray(set.boundaries))
            .put("boundary_source", set.boundarySource)
            .put("accepted_definition", "profile.observe accepted; does not imply FSM progression, a completed repetition or correct form")
        if (reason != null) {
            return result.put("status", if (originalProfile?.isometric == true) "hold" else "skipped")
                .put("reason", reason).put("counts", JSONObject.NULL).put("accepted_count", 0)
                .put("rejected_count", 0).put("not_evaluated_count", set.frames.size)
                .put("frames", JSONArray(set.frames.mapIndexed { index, frame ->
                    baseFrame(index, frame).put("view", JSONObject.NULL).put("accepted", JSONObject.NULL)
                        .put("reason", reason).put("events", JSONArray()).put("profile_signals", JSONObject())
                }))
        }
        val profile = requireNotNull(originalProfile).withLegacySignalConstraints(
            RepCounter.forExercise(originalProfile.exercise)?.signal,
            rules.rulesFor(originalProfile.exercise).firstOrNull { it.kind == "rep" }?.repConfig)
        val selectedPattern = requireNotNull(pattern)
        val tracker = requireNotNull(profile.createTracker(selectedPattern))
        val signals = selectedSignals(profile, selectedPattern)
        val recentView = RecentRepView()
        val applied = BooleanArray(set.boundaries.size)
        var accepted = 0
        var previousHighWater: Long? = null
        val rejectionReasons = linkedMapOf<String, Int>()
        val frames = JSONArray()
        set.frames.forEachIndexed { index, frame ->
            val boundaryIndices = set.boundaries.indices.filter { !applied[it] && set.boundaries[it] <= frame.timeMs }
            boundaryIndices.forEach { boundaryIndex ->
                tracker.resetCycle(); recentView.reset(); applied[boundaryIndex] = true
            }
            // 순서 위반도 엔진에 그대로 전달한다. 아래 시각 진단은 입력을 고치는 데 사용하지 않는다.
            val timestampIncreasing = previousHighWater?.let { frame.timeMs > it } ?: true
            if (previousHighWater == null || frame.timeMs > previousHighWater!!) previousHighWater = frame.timeMs
            val view = if (profile.floor) null else recentView.add(frame.timeMs, frame.features)
            val observation = profile.observe(frame.features, selectedPattern, view, qualityOk = frame.features.isNotEmpty())
            val events = tracker.onFrame(frame.timeMs, observation.features)
            if (observation.accepted) accepted++ else {
                val rejectedBecause = observation.reason ?: "profile_rejected_without_reason"
                rejectionReasons[rejectedBecause] = (rejectionReasons[rejectedBecause] ?: 0) + 1
            }
            val signalValues = JSONObject()
            signals.forEach { (side, signal) ->
                val raw = frame.features[signal.feature]
                val passed = observation.features[signal.feature]
                signalValues.put(side.name, JSONObject().put("feature", signal.feature)
                    .put("input_present", frame.features.containsKey(signal.feature))
                    .put("raw_value", finite(raw)).put("profile_value", finite(passed))
                    .put("plausible", passed != null && passed.isFinite() &&
                        (signal.plausibleMin == null || passed >= signal.plausibleMin) &&
                        (signal.plausibleMax == null || passed <= signal.plausibleMax)))
            }
            frames.put(baseFrame(index, frame).put("view", view?.name ?: JSONObject.NULL)
                .put("accepted", observation.accepted).put("reason", observation.reason ?: JSONObject.NULL)
                .put("timestamp_strictly_increasing", timestampIncreasing).put("timestamp_nonnegative", frame.timeMs >= 0)
                .put("applied_boundary_indices", JSONArray(boundaryIndices))
                .put("profile_signals", signalValues)
                .put("required_features", JSONObject().also { values -> profile.requiredFeatures(selectedPattern).sorted().forEach {
                    values.put(it, finite(frame.features[it]))
                } }).put("events", JSONArray(events.map(::eventJson))))
        }
        return result.put("status", "replayed").put("reason", JSONObject.NULL)
            .put("profile", JSONObject().put("variant", profile.variant).put("floor", profile.floor)
                .put("validated", profile.validated).put("count_definition", profile.countDefinition)
                .put("limitations", profile.limitations).put("signals", JSONObject().also { obj ->
                    signals.forEach { (side, signal) -> obj.put(side.name, signalJson(signal)) }
                }))
            .put("accepted_count", accepted).put("rejected_count", set.frames.size - accepted).put("not_evaluated_count", 0)
            .put("rejected_reasons", JSONObject(rejectionReasons as Map<*, *>)).put("counts", countsJson(tracker.counts))
            .put("observed_period_ms", tracker.observedPeriodMs ?: JSONObject.NULL)
            .put("events", JSONArray(tracker.events.map(::eventJson))).put("frames", frames)
            .put("unreached_boundary_indices", JSONArray(applied.indices.filter { !applied[it] }))
    }

    private fun selectedSignals(profile: ExerciseRepProfile, pattern: RepMovementPattern): Map<RepSide, RepSignal> = when {
        pattern == RepMovementPattern.LEFT_ONLY -> mapOf(RepSide.LEFT to requireNotNull(profile.leftSignal))
        pattern == RepMovementPattern.RIGHT_ONLY -> mapOf(RepSide.RIGHT to requireNotNull(profile.rightSignal))
        profile.commonSignal != null -> mapOf(RepSide.UNKNOWN to profile.commonSignal)
        else -> mapOf(RepSide.LEFT to requireNotNull(profile.leftSignal), RepSide.RIGHT to requireNotNull(profile.rightSignal))
    }

    private fun baseFrame(index: Int, frame: Frame) = JSONObject().put("index", index).put("t_ms", frame.timeMs)
        .put("feature_count", frame.features.size).put("features_field_present", frame.json.has("features"))
        .put("nonfinite_feature_keys", JSONArray(frame.features.filterValues { !it.isFinite() }.keys.toList()))

    private fun eventJson(event: ExerciseRepEvent): JSONObject = JSONObject().put("t_ms", event.timeMs)
        .put("side", event.side.name).put("counts", countsJson(event.counts)).put("cycles", JSONObject().also { cycles ->
            event.toRepRecord().details.orEmpty().forEach { (side, cycle) ->
                cycles.put(side.name, JSONObject().put("signal_feature", cycle.signalFeature).put("t_ms", cycle.tMs)
                    .put("raw_min", finite(cycle.cycleMin)).put("raw_max", finite(cycle.cycleMax))
                    .put("rom_reference_pass", cycle.valid ?: JSONObject.NULL))
            }
        })

    private fun signalJson(signal: RepSignal): JSONObject = JSONObject().put("feature", signal.feature)
        .put("min_amp", signal.minAmp.toDouble()).put("plausible_min", finite(signal.plausibleMin))
        .put("plausible_max", finite(signal.plausibleMax)).put("rom_direction", signal.romDirection ?: JSONObject.NULL)
        .put("rom_threshold", finite(signal.romThreshold)).put("rom_validated", signal.romValidated)

    private fun countsJson(counts: ExerciseRepCounts) = JSONObject().put("total", counts.total)
        .put("left", counts.left).put("right", counts.right).put("both", counts.both).put("unknown", counts.unknown)

    private fun finite(value: Float?): Any = value?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL

    private fun integerMs(value: Any, label: String): Long {
        check(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) {
            "$label 값이 유한한 정수 밀리초가 아닙니다"
        }
        return value.toLong()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
