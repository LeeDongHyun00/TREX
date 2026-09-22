package com.example.trex_kotlin.validation

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun newId(): String = UUID.randomUUID().toString()
fun File.sha256(): String = inputStream().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) { val n = stream.read(buffer); if(n < 0) break; digest.update(buffer,0,n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
fun writeJson(file: File, value: JSONObject) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(value.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
    catch(t: Throwable) { atomic.failWrite(stream); throw t }
}
fun readJson(file: File) = JSONObject(file.readText())
fun JSONArray.rows(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun FloatArray.jsonArray() = JSONArray().also { a -> forEach { a.put(if(it.isFinite()) it.toDouble() else JSONObject.NULL) } }
fun JSONArray.floats() = FloatArray(length()) { if(isNull(it)) Float.NaN else getDouble(it).toFloat() }

/** 로컬 연구 원본. 자동 삭제하지 않으며 외부 공유는 사용자가 지정한 문서로만 수행한다. */
class StudyStore(private val context: Context) {
    val root = File(context.filesDir,"validation").apply { mkdirs() }
    fun folder(id: String): File { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return File(root,id) }
    fun sessions(): List<File> = root.listFiles().orEmpty().filter { File(it,"session.json").exists() }.sortedByDescending { it.lastModified() }
    fun create(person: String, day: String, split: String, exercise: String, pattern: String,
        view: String, floor: Boolean, fromFloor: Boolean, source: String): File {
        require(person.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "수행자 코드는 영문·숫자·밑줄로 입력해 주세요." }
        LocalDate.parse(day)
        require(split in listOf("train","calibration","test"))
        val profile = com.trex.engine.ExerciseCatalog.profiles.firstOrNull {it.exercise == exercise}
            ?: error("검증 대상 26개 운동 중에서 선택해 주세요.")
        require(profile.allowedPatterns.any {it.name == pattern}) { "이 운동에서 지원하는 수행 방식을 선택해 주세요." }
        require(!floor || profile.floor && view == "측면") { "측면 확인과 촬영 방향이 일치해야 합니다." }
        sessions().forEach { dir ->
            val previous = readJson(File(dir,"session.json"))
            require(previous.getString("person") != person || previous.getString("split") == split) { "같은 수행자는 같은 데이터 분할을 사용해야 합니다." }
        }
        val dir = folder(newId()).apply { mkdirs() }
        writeJson(File(dir,"session.json"), JSONObject().put("schema",1).put("id",dir.name)
            .put("person",person).put("day",day).put("split",split).put("exercise",exercise)
            .put("pattern",pattern).put("view_declared",view).put("floor_side_confirmed",floor)
            .put("deadlift_from_floor",fromFloor).put("source",source).put("consent_local_video",true)
            .put("capture_launch_utc_ms",if(source == "camera") System.currentTimeMillis() else JSONObject.NULL)
            .put("frame_utc", "UNKNOWN").put("status","AWAITING_VIDEO")
            .put("device", "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}/${android.os.Build.VERSION.SDK_INT}"))
        return dir
    }
    fun importVideo(dir: File, uri: Uri) {
        val temp = File(dir,"video.partial")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "영상을 열 수 없습니다." }
            temp.outputStream().use { output ->
                val buffer = ByteArray(65536); var size = 0L
                while(true) {
                    val n = input.read(buffer); if(n < 0) break
                    size += n
                    require(size <= 2_000_000_000 && dir.usableSpace > 30_000_000) { "영상은 2GB 이하이며 저장 공간이 필요합니다." }
                    output.write(buffer,0,n)
                }
            }
        }
        require(temp.length()>0 && temp.renameTo(File(dir,"video.mp4"))) { "영상 복사를 마치지 못했습니다." }
        finishVideo(dir)
    }
    fun finishVideo(dir: File) {
        val video = File(dir,"video.mp4")
        require(video.exists() && video.length()>0) { "저장된 영상이 없습니다. 촬영을 다시 시작해 주세요." }
        val info = VideoFrames.metadata(video)
        require(info.durationMs in 1..600_000) { "1회 영상은 10분 이내로 준비해 주세요." }
        val m = readJson(File(dir,"session.json"))
        m.put("video_sha256",video.sha256()).put("duration_ms",info.durationMs)
            .put("rotation",info.rotation).put("status","VIDEO_READY").put("video_ready_utc_ms",System.currentTimeMillis())
        writeJson(File(dir,"session.json"),m)
    }
    fun latest(dir: File, kind: String): File? = File(dir,kind).listFiles().orEmpty()
        .filter { if(kind == "labels") it.extension == "json" else File(it,"manifest.json").exists() && readJson(File(it,"manifest.json")).optString("status") == "COMPLETE" }
        .maxByOrNull { it.lastModified() }
    fun saveTruth(dir: File, truth: Truth): File {
        val m = readJson(File(dir,"session.json")); truth.validate(m.getLong("duration_ms"))
        require(truth.repsReviewed || truth.holdsReviewed || truth.forms.isNotEmpty()) { "반복·유지·자세 중 하나 이상의 정답을 검토해 주세요." }
        val path = File(dir,"labels/${System.currentTimeMillis()}-${newId()}.json")
        writeJson(path, truthDocument(dir,truth).put("source",if(m.getString("source") == "synthetic_test") "synthetic_fixture" else "human_video_review"))
        File(dir,"draft.json").delete()
        return path
    }
    fun saveDraft(dir: File, truth: Truth, baseLabel: String?) { writeJson(File(dir,"draft.json"),truthDocument(dir,truth).put("source","unconfirmed_draft").put("base_label",baseLabel ?: "")) }
    private fun truthDocument(dir: File, truth: Truth): JSONObject {
        val m=readJson(File(dir,"session.json"))
        return JSONObject().put("schema",1).put("video_sha256",m.getString("video_sha256"))
            .put("reviewer",truth.reviewer).put("start_ms",truth.startMs).put("end_ms",truth.endMs)
            .put("reps_reviewed",truth.repsReviewed).put("prediction_exposed",truth.predictionExposed || File(dir,"prediction-exposed.json").exists())
            .put("created_utc_ms",System.currentTimeMillis()).put("source","human_video_review")
            .put("holds_reviewed",truth.holdsReviewed).put("holds",JSONArray().also { a -> truth.holds.forEach { a.put(JSONObject().put("start_ms",it.startMs).put("end_ms",it.endMs)) } })
            .put("reps",JSONArray().also { a -> truth.reps.forEach { a.put(JSONObject().put("start_ms",it.startMs).put("end_ms",it.endMs).put("side",it.side)) } })
            .put("forms",JSONArray().also { a -> truth.forms.forEach { a.put(JSONObject().put("start_ms",it.startMs).put("end_ms",it.endMs)
                .put("item",it.item).put("verdict",it.verdict).put("side",it.side).put("phase",it.phase).put("note",it.note)) } })
    }
    fun deleteSession(dir: File) {
        require(dir.canonicalFile.parentFile == root.canonicalFile && dir.name.matches(Regex("[a-zA-Z0-9-]+")))
        check(dir.deleteRecursively()) { "일부 자료를 지우지 못했습니다." }
    }
    fun truth(file: File): Truth = readJson(file).let { o -> Truth(o.getString("reviewer"),o.getLong("start_ms"),o.getLong("end_ms"),o.getBoolean("reps_reviewed"),
        o.getJSONArray("reps").rows().map { TruthRep(it.getLong("start_ms"),it.getLong("end_ms"),it.getString("side")) },
        o.getJSONArray("forms").rows().map { TruthForm(it.getLong("start_ms"),it.getLong("end_ms"),it.getString("item"),it.getString("verdict"),it.getString("side"),it.getString("phase"),it.getString("note")) },
        o.optBoolean("prediction_exposed"),o.optBoolean("holds_reviewed"),o.optJSONArray("holds")?.rows()?.map { TimeSpan(it.getLong("start_ms"),it.getLong("end_ms")) }.orEmpty()) }
    fun export(dir: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri).use { raw ->
            requireNotNull(raw) { "저장 위치를 열 수 없습니다." }
            ZipOutputStream(raw.buffered()).use { zip ->
                val hashes = JSONObject()
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".partial") }.sortedBy { it.path }.forEach { file ->
                    val name = file.relativeTo(dir).invariantSeparatorsPath
                    hashes.put(name,file.sha256()); zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("checksums.json")); zip.write(hashes.toString(2).toByteArray()); zip.closeEntry()
            }
        }
    }
}
