package com.example.trex_kotlin.validation

import android.content.Context
import com.example.trex_kotlin.BuildConfig
import com.example.trex_kotlin.posture.*
import com.trex.engine.ExerciseRepProfiles
import com.trex.engine.LandmarkFeatures
import com.trex.engine.LandmarkFrame
import com.trex.engine.LabEngine
import com.trex.engine.MovementContracts
import com.trex.engine.RepMovementPattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import com.example.trex_kotlin.validation.legacy.RepCounter as FrozenRepCounter

class StudyProcessor(private val context: Context) {
    private val store = StudyStore(context)
    fun rules(): PostureRuleSet {
        val standing = PostureRuleSet.load(context)
        val floor = PostureRuleSet.load(context,FLOOR_RULES_ASSET)
        return PostureRuleSet(standing.version+"/"+floor.version,"",standing.rules+floor.rules)
    }
    private fun assetHash(path: String): String = context.assets.open(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while(true) { val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun extract(dir: File, cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        val session = readJson(File(dir,"session.json"))
        val video = File(dir,"video.mp4")
        require(video.sha256() == session.getString("video_sha256")) { "원본 영상 해시가 달라졌습니다." }
        val output = File(dir,"extractions/${newId()}").apply { mkdirs() }
        val manifest = JSONObject().put("status","RUNNING").put("source_fingerprint",BuildConfig.SOURCE_FINGERPRINT)
            .put("video_sha256",video.sha256()).put("model_sha256",assetHash(PoseModel.FULL.asset))
            .put("clock","decoder_presentation_time_us").put("sample_interval_us",200_000)
            .put("model",PoseModel.FULL.asset).put("delegate","CPU").put("running_mode","VIDEO")
            .put("max_image_dimension",640).put("image_conversion","YUV420_BT601_limited_nearest/1")
            .put("inference_time_origin","video_relative_ms_plus_1").put("landmarks_are_ground_truth",false)
        writeJson(File(output,"manifest.json"),manifest)
        val analyzer = PostureAnalyzer(context,PoseModel.FULL,preferGpu=false)
        val floor = FloorFeatureExtractor()
        val profile = ExerciseRepProfiles.forExercise(session.getString("exercise"))!!
        try {
            check(analyzer.ensureReady()) { analyzer.stats().error ?: "모델을 불러오지 못했습니다." }
            var count = 0
            File(output,"frames.partial").bufferedWriter().use { writer ->
                VideoFrames.decode(video,cancelled) { bitmap, pts, relative ->
                    val sample = analyzer.analyzeBitmap(bitmap,relative/1000+1)
                    check(sample.inferenceSucceeded) { "관절 추론이 실패했습니다. 미검출과 구분하여 이 실행을 중단합니다." }
                    val oldFeatures = if(profile.floor) floor.computeForExercise(profile.exercise,sample.normalizedXy,sample.visibility,sample.imageWidth,sample.imageHeight) else sample.features
                    val row = JSONObject().put("schema",1).put("pts_us",pts).put("relative_us",relative)
                        .put("detected",sample.detected).put("width",sample.imageWidth).put("height",sample.imageHeight)
                        .put("xy",sample.normalizedXy.jsonArray()).put("z",sample.normalizedZ.jsonArray())
                        .put("world",sample.rawWorld.jsonArray()).put("visibility",sample.visibility.jsonArray())
                        .put("raw_visibility",sample.rawVisibility.jsonArray()).put("raw_presence",sample.rawPresence.jsonArray())
                        .put("legacy_features",JSONObject(oldFeatures.filterValues { it.isFinite() }))
                        .put("infer_ms",sample.inferMs)
                    writer.appendLine(row.toString()); count++
                    if(count == 1) File(output,"cover.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it) }
                    if(count % 5 == 0) progress("관절 추출 ${relative/1_000_000} / ${session.getLong("duration_ms")/1000}초 · $count 표본")
                }
            }
            val frames = File(output,"frames.jsonl")
            check(File(output,"frames.partial").renameTo(frames))
            manifest.put("status","COMPLETE").put("frame_count",count).put("frames_sha256",frames.sha256())
            writeJson(File(output,"manifest.json"),manifest)
            return output
        } catch(t: Throwable) {
            manifest.put("status","FAILED").put("error",t.message); writeJson(File(output,"manifest.json"),manifest); throw t
        } finally { analyzer.close() }
    }

    fun evaluate(dir: File, extraction: File, labelFile: File, cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        val session = readJson(File(dir,"session.json"))
        val truth = store.truth(labelFile).also { it.validate(session.getLong("duration_ms")) }
        val source = readJson(File(extraction,"manifest.json"))
        require(source.getString("status") == "COMPLETE")
        require(source.getString("video_sha256") == session.getString("video_sha256") &&
            readJson(labelFile).getString("video_sha256") == source.getString("video_sha256")) { "영상과 정답의 출처가 다릅니다." }
        val frames = File(extraction,"frames.jsonl")
        require(frames.sha256() == source.getString("frames_sha256")) { "좌표 파일 해시가 다릅니다." }
        val run = File(dir,"runs/${newId()}").apply { mkdirs() }
        val manifest = JSONObject().put("status","RUNNING").put("source_fingerprint",BuildConfig.SOURCE_FINGERPRINT)
            .put("engine",com.trex.engine.ENGINE_VERSION).put("feedback_policy",V2FormFeedback.POLICY_VERSION)
            .put("legacy_commit","3322628a2c58ab3d02266d6e9e824ffb12bfd616")
            .put("comparison_scope","frozen_rep_core_with_current_features_vs_v2; shared_ship_window_reference_vs_v2_feedback; NOT_APK_EQUIVALENCE")
            .put("input_extraction",extraction.name).put("frames_sha256",frames.sha256()).put("labels",labelFile.name).put("labels_sha256",labelFile.sha256())
            .put("rules_mp_sha256",assetHash("posture/rules_mp_v0.json")).put("rules_floor_sha256",assetHash(FLOOR_RULES_ASSET))
            .put("rep_tolerance_ms",500).put("prediction_carry_ms",250).put("replay_mode","offline_common_landmarks_no_tts")
        writeJson(File(run,"manifest.json"),manifest)
        try {
            val base = ExerciseRepProfiles.forExercise(session.getString("exercise"))!!
            val profile = MovementContracts.deadliftStart(base,session.getBoolean("deadlift_from_floor"))
            val engine = LabEngine(profile,RepMovementPattern.valueOf(session.getString("pattern")))
            val extractor = LandmarkFeatures()
            val rules = rules()
            val feedback = V2FormFeedback(rules,base.exercise)
            val feedbackIds = feedback.summary().map { it.ruleId }
            val reference = PostureRuleSet("ship-window-reference/1","",rules.rulesFor(base.exercise,false).filter { it.kind == "window" })
            val old = FrozenRepCounter.forExercise(base.exercise)?.let { FrozenRepCounter(it.signal,completeOnReturn=true) }
            val oldReps = mutableListOf<PredRep>(); val newReps = mutableListOf<PredRep>()
            val holds = mutableListOf<TimeSpan>(); var previousHold = 0L
            val oldForms = mutableListOf<PredForm>(); val newForms = mutableListOf<PredForm>()
            val recent = ArrayDeque<Map<String,Float>>()
            var lastView: String? = null; var lastEpoch: Int? = null; var lastTime = -1L
            var finalOutput: com.trex.engine.EngineOutput? = null
            var n = 0
            File(run,"predictions.partial").bufferedWriter().use { writer ->
                frames.forEachLine { line ->
                    check(!cancelled.get()) { "평가를 중단했습니다. 원본과 정답은 보존했습니다." }
                    val raw = JSONObject(line)
                    val time = raw.getLong("relative_us")/1000
                    require(time > lastTime); lastTime = time
                    // 사람이 지정한 평가 구간은 채점에만 쓴다. 엔진의 준비를 정답 시각으로 도와주지 않는다.
                    val f = extractor.extract(LandmarkFrame(raw.getJSONArray("xy").floats(),raw.getJSONArray("world").floats(),
                        raw.getJSONArray("visibility").floats(),raw.getInt("width"),raw.getInt("height")),profile.floor)
                    val out = engine.process(time,f,f.isNotEmpty(),session.getBoolean("floor_side_confirmed"))
                    val heldDelta = out.observedHoldMs-previousHold
                    if(heldDelta>0) holds+=TimeSpan(time-heldDelta,time)
                    previousHold=out.observedHoldMs
                    if(out.phase == "UNOBSERVABLE" || lastView != out.view || lastEpoch != out.epoch) feedback.interrupt()
                    lastView=out.view; lastEpoch=out.epoch
                    val cue = if(out.phase != "UNOBSERVABLE") feedback.accept(time,f,out.events.isNotEmpty() || profile.isometric && out.observedHoldMs >= 1000) else null
                    finalOutput = out
                    out.events.forEach { newReps += PredRep(it.timeMs,it.side.name) }
                    val legacy = raw.getJSONObject("legacy_features").let { o -> o.keys().asSequence().associateWith { o.getDouble(it).toFloat() } }
                    val oldEvent = old?.onFrame(time,legacy[old.signal.feature]) == true
                    if(oldEvent) oldReps += PredRep(time)
                    recent.addLast(f); while(recent.size > 8) recent.removeFirst()
                    val agg = FeatureAggregator().also { a -> recent.forEach { a.add(it) } }
                    val referenceResults = reference.evaluate(base.exercise,agg,false,8)
                    val referenceRows = JSONArray()
                    referenceResults.forEach {
                        oldForms += PredForm(time,it.rule.id,it.verdict.name)
                        referenceRows.put(JSONObject().put("item",it.rule.id).put("verdict",it.verdict.name)
                            .put("value",it.value).put("threshold",it.rule.threshold).put("operator",it.rule.op).put("abstain_reason",it.abstainReason))
                    }
                    val evidence = feedback.evidence().associateBy { it.rule.id }
                    val newRows = JSONArray()
                    val items = feedback.current.associateBy { it.ruleId }
                    feedbackIds.forEach { id ->
                        if(id !in items) {
                            newForms += PredForm(time,id,"ABSTAIN")
                            newRows.put(JSONObject().put("item",id).put("verdict","ABSTAIN").put("abstain_reason","관측 또는 준비 확인 중"))
                        }
                    }
                    feedback.current.forEach {
                        newForms += PredForm(time,it.ruleId,it.overall.name)
                        val e = evidence[it.ruleId]
                        newRows.put(JSONObject().put("item",it.ruleId).put("verdict",it.overall.name).put("value",e?.recentValue)
                            .put("threshold",e?.rule?.threshold).put("operator",e?.rule?.op).put("abstain_reason",it.abstainReason)
                            .put("direction",it.direction?.name).put("evaluated_at_ms",feedback.evaluatedAtMs))
                    }
                    writer.appendLine(JSONObject().put("t_ms",time).put("features",JSONObject(f)).put("phase",out.phase)
                        .put("view",out.view).put("epoch",out.epoch).put("reason",out.status)
                        .put("observed_hold_ms",out.observedHoldMs)
                        .put("v2_rep_events",JSONArray().also { a -> out.events.forEach { a.put(JSONObject().put("t_ms",it.timeMs).put("side",it.side.name)) } })
                        .put("legacy_rep_event",oldEvent).put("reference_form",referenceRows).put("v2_form",newRows)
                        .put("cue_candidate",cue).put("voice_delivered",false).toString())
                    n++; if(n%100 == 0) progress("동일 좌표 재생 · $n 표본")
                }
            }
            check(File(run,"predictions.partial").renameTo(File(run,"predictions.jsonl")))
            fun repJson(score: RepScore?): Any = score?.let { JSONObject().put("truth",it.truth).put("predicted",it.predicted)
                .put("matched",it.matched).put("missed",it.missed).put("extra",it.extra).put("mean_timing_error_ms",it.meanTimingErrorMs ?: JSONObject.NULL) } ?: JSONObject.NULL
            fun formJson(values: List<FormScore>) = JSONArray().also { a -> values.forEach { s ->
                val label = rules.rules.firstOrNull { it.id == s.item }?.let { "${it.condition} · ${it.baseFeature}" } ?: s.item.removePrefix("custom:")
                a.put(JSONObject().put("item",s.item).put("label",label).put("normal_ms",s.normalMs).put("error_ms",s.errorMs).put("unknown_truth_ms",s.unknownTruthMs)
                    .put("false_alarm_ms",s.falseAlarmMs).put("detected_error_ms",s.detectedErrorMs).put("judged_ms",s.judgedMs)
                    .put("coverage",s.coverage ?: JSONObject.NULL).put("false_alarm_rate",s.falseAlarmRate ?: JSONObject.NULL).put("sensitivity",s.sensitivity ?: JSONObject.NULL))
            } }
            val report = JSONObject().put("schema",1).put("person",session.getString("person")).put("day",session.getString("day"))
                .put("split",session.getString("split")).put("prediction_exposed",truth.predictionExposed)
                .put("legacy_rep_supported",old != null).put("v2_rep_supported",!base.isometric)
                .put("legacy_reps",if(old == null) JSONObject.NULL else repJson(Evaluation.reps(truth,oldReps)))
                .put("v2_reps",if(base.isometric) JSONObject.NULL else repJson(Evaluation.reps(truth,newReps)))
                .put("v2_side_reps",if(base.isometric || truth.reps.any { it.side in setOf("UNKNOWN","COMMON") }) JSONObject.NULL else repJson(Evaluation.reps(truth,newReps,matchSide=true)))
                .put("reference_form",formJson(Evaluation.forms(truth,oldForms))).put("v2_form",formJson(Evaluation.forms(truth,newForms)))
                .put("v2_hold",if(!base.isometric) JSONObject.NULL else Evaluation.hold(truth,holds)?.let {
                    JSONObject().put("truth_ms",it.truthMs).put("predicted_ms",it.predictedMs).put("overlap_ms",it.overlapMs).put("missed_ms",it.missedMs).put("extra_ms",it.extraMs)
                } ?: JSONObject.NULL)
                .put("v2_observed_hold_ms",finalOutput?.observedHoldMs).put("v2_accepted_frames",finalOutput?.acceptedFrames)
                .put("total_frames",n).put("warning","사람 1세션의 재생 결과입니다. 전체 정확도·정자세·음성 교정 효과를 인증하지 않습니다.")
            writeJson(File(run,"report.json"),report)
            manifest.put("status","COMPLETE").put("predictions_sha256",File(run,"predictions.jsonl").sha256())
            writeJson(File(run,"manifest.json"),manifest)
            return run
        } catch(t: Throwable) {
            manifest.put("status","FAILED").put("error",t.message); writeJson(File(run,"manifest.json"),manifest); throw t
        }
    }
}
