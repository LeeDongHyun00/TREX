package com.example.trex_kotlin.posture

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 실제 단말에서 앱 엔진을 실행한다. 합성 시각은 시계열 정확도 정답으로 사용하지 않는다.
 * 평가 패키지(-PpostureReplay)에서만 실행해 기존 앱 데이터와 분리한다. TTS는 실제로 발화하지 않는다. */
class AiHubReplayTest {
    @Test(timeout = 1_200_000)
    fun replayDataset() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.packageName.endsWith(".replay")) { "평가 전용 패키지에서 실행하세요" }
        val dir = File(context.getExternalFilesDir(null), "aihub_replay").apply { mkdirs() }
        val bundle = File(dir, "replay.zip")
        check(bundle.isFile) { "replay.zip을 먼저 단말에 복사하세요" }
        val standing = PostureRuleSet.load(context)
        val floor = PostureRuleSet.load(context, FLOOR_RULES_ASSET)
        val rules = PostureRuleSet(standing.version + "+" + floor.version, standing.generated, standing.rules + floor.rules)
        val resultFile = File(dir, "results.jsonl")
        val manifestHash: String
        var processed = 0
        ZipFile(bundle).use { zip ->
            val text = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
            manifestHash = sha(text.toByteArray())
            val manifest = JSONObject(text)
            val sequences = manifest.getJSONArray("sequences")
            assertTrue("빈 데이터셋을 성공 처리하지 않는다", sequences.length() > 0)
            val step = manifest.getLong("frame_interval_ms")
            val done = if (resultFile.isFile) resultFile.readLines().map { JSONObject(it) }.onEach {
                check(it.getString("manifest_sha256") == manifestHash) { "다른 데이터 결과와 혼합할 수 없습니다" }
            }.map { it.getString("id") }.toSet() else emptySet()
            for (seqIndex in 0 until sequences.length()) {
                val seq = sequences.getJSONObject(seqIndex)
                if (seq.getString("id") in done) { processed++; continue }
                val ex = seq.getString("exercise")
                val isFloor = ex in FloorTemporal.exercises
                val analyzer = PostureAnalyzer(context, PoseModel.FULL, preferGpu = true)
                try {
                    check(analyzer.ensureReady()) { analyzer.stats().error ?: "모델 생성 실패" }
                    val extractor = FloorFeatureExtractor()
                    val coach = LiveCoach(rules, ex, baseline = null, requireAnchor = true, speakBeta = false)
                    // 앱 세션과 같은 카운터 구성 — PostureLive 도 이 팩토리를 부른다(복귀형·maxGap 1.5 s·규칙/바닥 ROM, spec §58).
                    // 손으로 만든 구성(예전: 반전형·서서 하는 종목 maxGap 무제한)은 앱과 다른 카운터·앵커 시점을 쟀다.
                    val cfg = rules.rulesFor(ex).firstOrNull { it.kind == "rep" }?.repConfig
                    val counter = RepCounter.forSession(ex, cfg?.direction, cfg?.threshold, floor = isFloor)
                    val samples = ArrayList<PoseSample>()
                    val times = ArrayList<Long>()
                    val reps = ArrayList<RepRecord>()
                    val full = FeatureAggregator()
                    var firstMeasured: Long? = null
                    var anchor: Long? = null
                    val frameOutput = JSONArray()
                    val images = seq.getJSONArray("images")
                    for (i in 0 until images.length()) {
                        val startNs = System.nanoTime()
                        val entry = zip.getEntry(images.getString(i)) ?: error("이미지 누락")
                        val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) } ?: error("이미지 디코딩 실패")
                        val t = 1L + i * step
                        val raw = try { analyzer.analyzeBitmap(bitmap, t) } finally { bitmap.recycle() }
                        val sample = if (isFloor && raw.detected) raw.withFeatures(extractor.computeForExercise(ex,raw.normalizedXy, raw.visibility, raw.imageWidth, raw.imageHeight)) else raw
                        samples.add(sample); times.add(t); full.add(sample.features)
                        if (sample.detected) {
                            if (counter?.onFrame(t, sample.features[counter.signal.feature]) == true) {
                                // 앱처럼 발표된 사이클마다 한 회 — 새 코어는 한 프레임에 둘을 발표할 수 있다(레거시는 이 프레임의 한 사이클)
                                val cycles = counter.newlyPublished.map { Triple(it.tMs, it.min, it.max) }
                                    .ifEmpty { listOf(Triple(t, counter.lastCycleMin, counter.lastCycleMax)) }
                                for ((at, lo, hi) in cycles) reps += RepRecord(at, lo, hi, counter.signal.isValidRep(lo, hi))
                                if (coach.anchor()) anchor = t
                            }
                            if (sample.features.isNotEmpty() && firstMeasured == null) firstMeasured = t
                            val wait = if (counter == null) 4000 else 10000
                            if (!coach.isAnchored && firstMeasured != null && t - firstMeasured!! >= wait && coach.anchor()) anchor = t
                            coach.onFrame(sample.features); coach.evaluate(t)
                        }
                        frameOutput.put(JSONObject().put("t_ms", t).put("detected", sample.detected)
                            .put("infer_ms", sample.inferMs).put("decode_infer_features_ms", (System.nanoTime() - startNs) / 1e6)
                            .put("w", sample.imageWidth).put("h", sample.imageHeight)
                            .put("xy", floats(sample.normalizedXy)).put("visibility", floats(sample.visibility))
                            .put("features", JSONObject(sample.features.mapValues { it.value.toDouble() })))
                    }
                    val end = AssessmentWindow.end(times.last(), counter?.repTimesMs.orEmpty())
                    val live = PostureAssessment.evaluate(rules, ex, samples, times, anchor ?: Long.MAX_VALUE, end, reps)
                    val result = JSONObject().put("id", seq.getString("id")).put("exercise", ex)
                        .put("view", seq.getString("view")).put("clip_id", seq.getString("clip_id"))
                        .put("manifest_sha256", manifestHash).put("rules_version", rules.version)
                        .put("delegate", analyzer.stats().delegate).put("model", Build.MODEL)
                        .put("timestamp_source", manifest.getString("timestamp_source"))
                        .put("anchor_t_ms", anchor ?: JSONObject.NULL).put("assessment_end_t_ms", end)
                        .put("detected_frames", samples.count { it.detected }).put("measured_frames", samples.count { it.features.isNotEmpty() })
                        .put("reps", reps.size).put("live_results", encode(live))
                        .put("clip_results", encode(rules.evaluate(ex, full)))
                        .put("frames", frameOutput)
                    resultFile.appendText(result.toString() + "\n")
                    processed++
                    File(dir, "progress.json").writeText(JSONObject().put("done", processed).put("total", sequences.length()).put("last", seq.getString("id")).toString())
                    if (processed % 10 == 0) instrument.sendStatus(0, Bundle().apply { putString("stream", "replay $processed/${sequences.length()}\n") })
                } finally {
                    analyzer.close()
                }
            }
        }
        assertTrue(processed > 0)
        File(dir, "complete.json").writeText(JSONObject().put("processed", processed).put("manifest_sha256", manifestHash).toString())
    }

    private fun floats(values: FloatArray) = JSONArray().apply {
        values.forEach { put(if (it.isFinite()) it.toDouble() else JSONObject.NULL) }
    }

    private fun encode(results: List<RuleResult>) = JSONArray().apply {
        results.forEach { r -> put(JSONObject().put("id", r.rule.id).put("condition", r.rule.condition)
            .put("kind", r.rule.kind).put("status", r.rule.status.name).put("verdict", r.verdict.name)
            .put("value", r.value?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
            .put("n", r.sampleCount).put("abstain_reason", r.abstainReason ?: JSONObject.NULL)) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
