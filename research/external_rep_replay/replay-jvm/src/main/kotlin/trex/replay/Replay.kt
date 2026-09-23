package trex.replay

import com.example.trex_kotlin.posture.Joints
import com.example.trex_kotlin.posture.PoseFrame
import com.example.trex_kotlin.posture.RepCounter
import com.example.trex_kotlin.posture.RepSignal
import com.example.trex_kotlin.posture.Vec3
import com.example.trex_kotlin.posture.checkUpSanity
import com.example.trex_kotlin.posture.mid
import java.io.File

/*
 * 외부 코퍼스(MM-Fit·REHAB24-6) 재생기 — 영상 추론 결과를 **현재 앱의** RepCounter 에 그대로 먹인다.
 *
 * 캡처 형식 (extract_mediapipe.py 가 쓴다, 탭 구분):
 *   # ...                                 주석
 *   H <key>=<value> ...                   메타
 *   F <tMs> <poses> [<i>:<x>,<y>,<vis>,<pres>,<wx>,<wy>,<wz> ...]
 *     x,y = 정규화 이미지 좌표, vis·pres = 정규화 랜드마크의 visibility·presence, wx..wz = 월드 좌표(m)
 *
 * 추론 이후 처리는 PostureAnalyzer.analyzeBitmap 과 같은 순서다. 그 파일은 MediaPipe·Android 타입에 묶여
 * JVM 에서 컴파일되지 않으므로 절차만 옮겼다 — 피처·카운터 자체는 앱 소스를 그대로 컴파일한 것이다.
 *   1) vis = min(visibility, presence). 값이 없으면 1 (Android 의 Optional.orElse(1f) 와 같다)
 *   2) vis < 0.5 인 관절은 버린다 (PostureAnalyzer.MIN_VISIBILITY)
 *   3) 월드 좌표 m → cm, y·z 부호 반전 (spec §3)
 *   4) Joints.SINGLE / Joints.PAIR 로 관절 사전을 만든다
 *   5) checkUpSanity 로 up 뒤집힘을 보정한다. 영상에는 IMU 가 없으므로 up = 화면 세로축(0,1,0) —
 *      앱이 중력 센서를 못 쓸 때의 폴백(SCREEN_UP)과 같다
 *   6) PoseFrame(joints, up).features()
 * ViewEstimator.frameFeatures 는 렙 신호와 무관해 계산하지 않는다.
 *
 * 카운터 구성은 PostureLive 의 서서 하는 종목 경로와 같다(rules_mp_v0.json 에 kind=rep 규칙이 없다):
 *   live     = RepCounter(signal, maxGapMs = 1500, completeOnReturn = true)   ← 앱 세션이 실제로 쓰는 것
 *   reversal = RepCounter.forExercise(exercise) 기본 구성(반전 확정)         ← 진단용, 앱 세션은 쓰지 않는다
 * 앱은 사람이 검출된 프레임에서만 onFrame 을 부른다(검출 안 된 프레임은 카운터를 건너뛴다). 여기서도 같다.
 */

private const val MIN_VISIBILITY = 0.5f
// PostureAnalyzer.kt 의 MP_LANDMARK_COUNT 와 같다 (그 파일은 Android 의존이라 컴파일하지 않는다).
private const val MP_LANDMARK_COUNT = 33
private val SCREEN_UP = Vec3(0f, 1f, 0f)

/** 한 프레임의 추론 결과. poses = 0 이면 landmarks 는 비어 있다. */
class CaptureFrame(val tMs: Long, val poses: Int, val image: Array<FloatArray?>, val world: Array<Vec3?>)

class Capture(val meta: Map<String, String>, val frames: List<CaptureFrame>)

fun readCapture(file: File): Capture {
    val meta = LinkedHashMap<String, String>()
    val frames = ArrayList<CaptureFrame>()
    file.forEachLine { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachLine
        val parts = line.split('\t')
        when (parts[0]) {
            "H" -> parts.drop(1).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) meta[kv.substring(0, i)] = kv.substring(i + 1)
            }
            "F" -> {
                val image = arrayOfNulls<FloatArray>(MP_LANDMARK_COUNT)
                val world = arrayOfNulls<Vec3>(MP_LANDMARK_COUNT)
                for (entry in parts.drop(3)) {
                    val colon = entry.indexOf(':')
                    val idx = entry.substring(0, colon).toInt()
                    val v = entry.substring(colon + 1).split(',').map { it.toFloat() }
                    image[idx] = floatArrayOf(v[0], v[1], v[2], v[3])
                    world[idx] = Vec3(v[4], v[5], v[6])
                }
                frames += CaptureFrame(parts[1].toLong(), parts[2].toInt(), image, world)
            }
        }
    }
    return Capture(meta, frames)
}

/** PostureAnalyzer.analyzeBitmap 의 추론 후처리. 사람이 없으면 null (앱의 detected=false). */
fun frameFeatures(frame: CaptureFrame, stats: FrameStats): Map<String, Float>? {
    if (frame.poses < 1) return null
    if (frame.image.any { it == null } || frame.world.any { it == null }) return null
    val vis = FloatArray(MP_LANDMARK_COUNT) { i ->
        val lm = frame.image[i]!!
        val v = if (lm[2].isNaN()) 1f else lm[2]
        val pr = if (lm[3].isNaN()) 1f else lm[3]
        minOf(v, pr)
    }
    val pts = arrayOfNulls<Vec3>(MP_LANDMARK_COUNT)
    for (i in 0 until MP_LANDMARK_COUNT) {
        if (vis[i] < MIN_VISIBILITY) continue
        val p = frame.world[i]!!
        pts[i] = Vec3(p.x * 100f, -p.y * 100f, -p.z * 100f)
    }
    val joints = HashMap<String, Vec3?>(24)
    for ((name, idx) in Joints.SINGLE) joints[name] = pts.getOrNull(idx)
    for ((name, pair) in Joints.PAIR) {
        val a = pts.getOrNull(pair.first)
        val b = pts.getOrNull(pair.second)
        joints[name] = if (a != null && b != null) mid(a, b) else a ?: b
    }
    val sanity = checkUpSanity(joints, SCREEN_UP)
    val up = if (sanity.flipped) (SCREEN_UP * -1f).unit() ?: SCREEN_UP else SCREEN_UP
    if (sanity.flipped) stats.upFlipped++
    return PoseFrame(joints, up).features()
}

class FrameStats {
    var frames = 0
    var detected = 0
    var withValue = 0
    var upFlipped = 0
}

/** 매니페스트 한 줄 = 캡처 하나 × 카운터 구성 하나. feature 가 비어 있으면 앱의 현재 신호를 쓴다. */
data class Job(val id: String, val capture: String, val exercise: String, val mode: String, val feature: String?, val minAmp: Float?)

fun counterFor(job: Job): RepCounter? {
    val base = RepCounter.forExercise(job.exercise) ?: return null
    val signal = if (job.feature != null) {
        // 신호 교체 진단: ROM 기준은 원래 신호의 단위라 떼어낸다(다른 피처에 붙이면 의미가 없다).
        RepSignal(job.feature, job.minAmp ?: base.signal.minAmp)
    } else base.signal
    return when (job.mode) {
        "live" -> RepCounter(signal, maxGapMs = 1500L, completeOnReturn = true)
        "reversal" -> RepCounter(signal)
        else -> error("unknown mode ${job.mode}")
    }
}

private val SERIES_FEATURES = listOf("knee_mean", "knee_minside", "knee_out_mean", "elbow_mean", "elbow_minside", "hip_mean")

fun run(job: Job, capture: Capture, seriesDir: File?): String {
    val rc = counterFor(job) ?: return json(mapOf("id" to job.id, "error" to "no counter for ${job.exercise}"))
    val stats = FrameStats()
    val repTimes = ArrayList<Long>()
    val cycles = ArrayList<String>()
    var valid = 0
    var invalid = 0
    val series = seriesDir?.let { StringBuilder("tMs\tvalue\t" + SERIES_FEATURES.joinToString("\t") + "\n") }
    for (frame in capture.frames) {
        stats.frames++
        val features = frameFeatures(frame, stats) ?: continue
        stats.detected++
        val value = features[rc.signal.feature]
        if (value != null) stats.withValue++
        series?.append(frame.tMs)?.append('\t')?.append(value ?: "")?.append('\t')
            ?.append(SERIES_FEATURES.joinToString("\t") { features[it]?.toString() ?: "" })?.append('\n')
        if (rc.onFrame(frame.tMs, value)) {
            val ok = rc.signal.isValidRep(rc.lastCycleMin, rc.lastCycleMax)
            if (ok != false) valid++ else invalid++
            repTimes += frame.tMs
            cycles += "[${num(rc.lastCycleMin)},${num(rc.lastCycleMax)},${ok ?: "null"}]"
        }
    }
    if (series != null) File(seriesDir, "${job.id}.tsv").writeText(series.toString())
    val first = capture.frames.firstOrNull()?.tMs
    val last = capture.frames.lastOrNull()?.tMs
    return json(linkedMapOf(
        "id" to job.id, "capture" to job.capture, "exercise" to job.exercise, "mode" to job.mode,
        "feature" to rc.signal.feature, "minAmp" to rc.signal.minAmp,
        "romDirection" to rc.signal.romDirection, "romThreshold" to rc.signal.romThreshold,
        "frames" to stats.frames, "detectedFrames" to stats.detected, "valueFrames" to stats.withValue,
        "upFlipped" to stats.upFlipped, "firstMs" to first, "lastMs" to last,
        "reps" to rc.reps, "valid" to valid, "invalid" to invalid,
        "repTimesMs" to Raw(repTimes.joinToString(",", "[", "]")),
        "publishedMs" to Raw(rc.repTimesMs.joinToString(",", "[", "]")),
        "cycles" to Raw(cycles.joinToString(",", "[", "]")),
    ))
}

private class Raw(val text: String)

private fun num(v: Float): String = if (v.isFinite()) v.toString() else "null"

private fun json(map: Map<String, Any?>): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
    val value = when (v) {
        null -> "null"
        is Raw -> v.text
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Float -> num(v)
        else -> v.toString()
    }
    "\"$k\":$value"
}

fun readManifest(file: File): List<Job> = file.readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val c = line.split('\t')
        Job(c[0], c[1], c[2], c[3], c.getOrNull(4)?.ifBlank { null }, c.getOrNull(5)?.ifBlank { null }?.toFloat())
    }

/** 사용법: replay <manifest.tsv> <results.jsonl> [series-dir] — 매니페스트의 캡처 경로는 매니페스트 기준 상대경로. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: replay <manifest.tsv> <results.jsonl> [series-dir]" }
    val manifest = File(args[0])
    val jobs = readManifest(manifest)
    val seriesDir = args.getOrNull(2)?.let { File(it).apply { mkdirs() } }
    val cache = HashMap<String, Capture>()
    File(args[1]).bufferedWriter().use { out ->
        for (job in jobs) {
            val path = File(job.capture).let { if (it.isAbsolute) it else File(manifest.absoluteFile.parentFile, job.capture) }
            val capture = cache.getOrPut(path.path) { readCapture(path) }
            out.write(run(job, capture, seriesDir))
            out.newLine()
        }
    }
    System.err.println("replayed ${jobs.size} jobs from ${cache.size} captures")
}
