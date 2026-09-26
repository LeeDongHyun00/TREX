package trex.replay

import com.example.trex_kotlin.posture.Joints
import com.example.trex_kotlin.posture.PoseFrame
import com.example.trex_kotlin.posture.RepCounter
import com.example.trex_kotlin.posture.RepCycle
import com.example.trex_kotlin.posture.RepFormSpecs
import com.example.trex_kotlin.posture.RepPolarity
import com.example.trex_kotlin.posture.RepSignal
import com.example.trex_kotlin.posture.RepSignals
import com.example.trex_kotlin.posture.Stance2d
import com.example.trex_kotlin.posture.Arm2d
import com.example.trex_kotlin.posture.Vec3
import com.example.trex_kotlin.posture.ViewEstimator
import com.example.trex_kotlin.posture.checkUpSanity
import com.example.trex_kotlin.posture.mid
import java.io.File

/*
 * 외부 코퍼스(MM-Fit·REHAB24-6) 재생기 — 영상 추론 결과를 **현재 앱의** RepCounter 에 그대로 먹인다.
 *
 * 캡처 형식 (extract_mediapipe.py 가 쓴다, 탭 구분):
 *   # ...                                 주석
 *   H <key>=<value> ...                   메타
 *   U <tMs> <x>,<y>,<z>                   (선택) 바로 다음 F 줄에 쓸 up 벡터 — 휴대폰 검증 로그(spec §61)의 프레임별 `up`
 *                                         (앱이 그 프레임 피처에 실제로 쓴 값 = IMU 중력축, 자가검증 보정 후). 없으면 SCREEN_UP
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
 *      앱이 중력 센서를 못 쓸 때의 폴백(SCREEN_UP)과 같다. 캡처에 U 줄이 있으면(휴대폰 검증 로그) 그 up 을 쓴다
 *   6) PoseFrame(joints, up).features() + ViewEstimator.frameFeatures(joints) — 앱과 같은 피처 사전(방향 피처는 렙 신호와
 *      무관하지만 --dump-features 가 로그의 피처 사전 전체와 견줄 수 있게 함께 낸다)
 * --dump-features <capture.cap> <out.jsonl>: 재생과 **같은 함수(frameFeatures)** 로 F 줄마다 {"t":ms,"detected":b,"features":{...}}
 * 한 줄 — 검증 로그의 좌표에서 피처를 다시 계산해 로그 피처와 견주는 무결성 검사(gate_a.py)와 합성 픽스처(make_phone_fixture.py)가 쓴다.
 *
 * 카운터 구성은 PostureLive 의 서서 하는 종목 경로와 같다(rules_mp_v0.json 에 kind=rep 규칙이 없다):
 *   live       = RepCounter.forSession(exercise, floor = false)            ← 앱 세션이 실제로 쓰는 것(같은 함수를 부른다)
 *                신호를 바꾼 진단(live+<피처>)만 RepCounter(signal, maxGapMs = 1500, completeOnReturn = true) 를 직접 만든다
 *   reversal   = RepCounter.forExercise(exercise) 기본 구성(반전 확정)     ← 진단용, 앱 세션은 쓰지 않는다
 *   hysteresis = live 신호에 극성을 켠 새 코어(REP_ENGINE_DESIGN.md §4.2·§4.3) ← 앱에서는 아직 꺼져 있다
 *                극성은 매니페스트 10열(로그의 reps.config.polarity) → 등록부 → 연구 표(RESEARCH_POLARITY) 순서. 셋 다 없으면
 *                돌리지 않고 오류를 적는다 — 틀린 극성은 모든 세트의 마지막 반복을 잃어 '만들어진 틀린 숫자' 가 된다(설계 §4.2·§13)
 * 재생 코퍼스(MM-Fit·REHAB24-6)는 서서 하는 종목뿐이라 floor = false 이고 규칙 rep 설정도 없다.
 * 앱은 사람이 검출된 프레임에서만 onFrame 을 부른다(검출 안 된 프레임은 카운터를 건너뛴다). 여기서도 같다.
 *
 * 피처 수준 캡처 (*.fcap — setlog_captures.py 가 휴대폰 세트 로그에서 만든다, 탭 구분):
 *   H <key>=<value> ...                   메타. loggedReps·loggedRepTimesMs·loggedSignal 이 있으면 파리티를 적는다
 *   X <tMs> [<피처>=<값> ...]              앱이 그 프레임에서 카운터에 넣은 피처 사전(로그 frames[].features) 그대로
 * 세트 로그의 프레임은 앱이 기록한 프레임 = 카운터가 본 프레임이다(검출·비일시정지). 그래서 X 줄마다 onFrame 을 부르고,
 * 피처가 없으면 값 null 로 부른다(앱과 같다). 추론 후처리는 이미 앱에서 끝났으므로 여기서 다시 하지 않는다.
 * 일시정지·카메라 전환의 resetCycle() 은 spec §58 이후 로그(reps.resets)에 있다. 메타 loggedResetsAfterMs(리셋 직전에 앱 카운터가
 * 처리한 마지막 프레임의 t_ms, none = 아직 없음)가 있으면 t > after 인 첫 프레임 앞에서 부른다 — 앱의 락 순서 그대로라, 추론 중에
 * 누른 전환(프레임 t_ms 는 추론 전 시각)도 어긋나지 않는다. after 가 없는 로그는 loggedResetsMs(누른 시각) 이후 첫 프레임 앞 —
 * 이때는 한 프레임 어긋날 수 있다. §58 이전 로그는 1.5 s 넘는 틈으로만 드러난다.
 * 파리티가 깨질 수 있는 나머지 경로: 로그의 피처 소수 5자리 반올림, 로그를 쓴 빌드와 지금 빌드의 카운터·신호 차이.
 *
 * 매니페스트 7~9열(선택): floor(1/0) · 규칙 rep 설정의 ROM 방향 · 임계값 — RepCounter.forSession 인자 그대로.
 * 10열(선택): hysteresis 구성의 극성 down/up — 새 코어로 센 로그의 reps.config.polarity.
 * 연구용 파생 신호 `<base>_minside_both` = 좌우가 둘 다 있을 때만 min(L, R) — 앱 피처가 아니다(설계 §4.4 컬 후보).
 */

private const val MIN_VISIBILITY = 0.5f
// PostureAnalyzer.kt 의 MP_LANDMARK_COUNT 와 같다 (그 파일은 Android 의존이라 컴파일하지 않는다).
private const val MP_LANDMARK_COUNT = 33
private val SCREEN_UP = Vec3(0f, 1f, 0f)

/** 한 프레임의 추론 결과. poses = 0 이면 landmarks 는 비어 있다. up = null 이면 SCREEN_UP(U 줄 없음 — 영상 캡처). */
class CaptureFrame(val tMs: Long, val poses: Int, val image: Array<FloatArray?>, val world: Array<Vec3?>, val up: Vec3? = null)

class Capture(val meta: Map<String, String>, val frames: List<CaptureFrame>)

/** 캡처 숫자. capture_format.py 는 값이 없는 visibility·presence 를 파이썬 표기 "nan" 으로 쓴다(자바 parseFloat 는 "NaN" 만 읽는다). */
private fun capFloat(text: String): Float = if (text.equals("nan", ignoreCase = true)) Float.NaN else text.toFloat()

fun readCapture(file: File): Capture {
    val meta = LinkedHashMap<String, String>()
    val frames = ArrayList<CaptureFrame>()
    // U 줄의 up — 바로 다음 F 줄 하나에만 쓴다(시각이 같아야 한다. 어긋나면 캡처가 깨진 것이라 멈춘다)
    var pendingUp: Pair<Long, Vec3>? = null
    file.forEachLine { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachLine
        val parts = line.split('\t')
        when (parts[0]) {
            "H" -> parts.drop(1).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) meta[kv.substring(0, i)] = kv.substring(i + 1)
            }
            "U" -> {
                check(pendingUp == null) { "U line without F line at t=${pendingUp?.first}" }
                val v = parts[2].split(',').map { it.toFloat() }
                pendingUp = parts[1].toLong() to Vec3(v[0], v[1], v[2])
            }
            "F" -> {
                val image = arrayOfNulls<FloatArray>(MP_LANDMARK_COUNT)
                val world = arrayOfNulls<Vec3>(MP_LANDMARK_COUNT)
                for (entry in parts.drop(3)) {
                    val colon = entry.indexOf(':')
                    val idx = entry.substring(0, colon).toInt()
                    val v = entry.substring(colon + 1).split(',').map(::capFloat)
                    image[idx] = floatArrayOf(v[0], v[1], v[2], v[3])
                    world[idx] = Vec3(v[4], v[5], v[6])
                }
                val t = parts[1].toLong()
                val up = pendingUp?.let { (ut, u) ->
                    check(ut == t) { "U line t=$ut does not match next F line t=$t" }
                    u
                }
                pendingUp = null
                frames += CaptureFrame(t, parts[2].toInt(), image, world, up)
            }
        }
    }
    check(pendingUp == null) { "trailing U line without F line" }
    return Capture(meta, frames)
}

/** 피처 수준 캡처의 한 프레임 — 앱이 카운터에 넣은 피처 사전(세트 로그 frames[].features). 비어 있을 수 있다. */
class FeatureFrame(val tMs: Long, val features: Map<String, Float>)

class FeatureCapture(val meta: Map<String, String>, val frames: List<FeatureFrame>)

/** *.fcap 읽기. 값이 비었거나 null·NaN 이면 그 피처는 없는 것으로 둔다(로그의 null = 앱에서 계산 불가). */
fun readFeatureCapture(file: File): FeatureCapture {
    val meta = LinkedHashMap<String, String>()
    val frames = ArrayList<FeatureFrame>()
    file.forEachLine { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachLine
        val parts = line.split('\t')
        when (parts[0]) {
            "H" -> parts.drop(1).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) meta[kv.substring(0, i)] = kv.substring(i + 1)
            }
            "X" -> {
                val features = HashMap<String, Float>()
                for (entry in parts.drop(2)) {
                    val eq = entry.lastIndexOf('=')
                    if (eq <= 0) continue
                    val v = entry.substring(eq + 1).toFloatOrNull() ?: continue
                    if (v.isFinite()) features[entry.substring(0, eq)] = v
                }
                frames += FeatureFrame(parts[1].toLong(), features)
            }
        }
    }
    return FeatureCapture(meta, frames)
}

/**
 * 카운터 입력값. 앱 피처가 있으면 그대로, 없으면 연구용 파생 신호만 계산한다.
 * `<base>_minside_both` = `<base>_L`·`<base>_R` 가 **둘 다** 있을 때만 작은 쪽 — 앱의 `_minside` 는 한쪽만 보여도
 * 그쪽 값을 쓰는데(PostureCore), 교대 컬에서 먼 팔이 가려지면 그 폴백이 과다 카운트를 만든다(설계 §4.4). 앱 동작이 아니다.
 */
fun signalValue(features: Map<String, Float>, name: String): Float? {
    features[name]?.let { return it }
    if (name.endsWith("_minside_both")) {
        val base = name.removeSuffix("_minside_both")
        val l = features["${base}_L"] ?: return null
        val r = features["${base}_R"] ?: return null
        return minOf(l, r)
    }
    return null
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
    val base = frame.up ?: SCREEN_UP
    val sanity = checkUpSanity(joints, base)
    val up = if (sanity.flipped) (base * -1f).unit() ?: base else base
    if (sanity.flipped) stats.upFlipped++
    // 앱 PostureAnalyzer 와 같은 순서·같은 함수 — 이미지 2D 발 너비(§62a 후속 3)
    val xy = FloatArray(MP_LANDMARK_COUNT * 2) { k -> frame.image[k / 2]!![k % 2] }
    // §62c: 컬의 2D 팔·몸통 피처(Arm2d)도 앱과 같은 순서·같은 함수. 캡처에는 이미지 크기가 없어 세로 480×640(0.75)을 가정한다
    val viewF = ViewEstimator.frameFeatures(joints)
    return PoseFrame(joints, up).features() + viewF + Stance2d.features(xy, vis, MIN_VISIBILITY) +
        Arm2d.features(xy, vis, MIN_VISIBILITY, Stance2d.DEFAULT_ASPECT, Arm2d.yawOf(viewF))
}

/**
 * --dump-features: F 줄마다 재생 경로와 같은 frameFeatures 결과를 한 줄씩. 사람 없음(poses 0·관절 누락)은 detected=false, features={}.
 * 값은 Float.toString 그대로(반올림 없음), NaN·무한대는 null — 비교하는 쪽이 로그의 소수 5자리 반올림을 허용 오차로 다룬다.
 */
fun dumpFeatures(capture: Capture, out: File): Int {
    val stats = FrameStats()
    out.bufferedWriter().use { w ->
        for (frame in capture.frames) {
            val feats = frameFeatures(frame, stats)
            w.write("{\"t\":${frame.tMs},\"detected\":${feats != null},\"features\":{")
            w.write(feats.orEmpty().entries.joinToString(",") { (k, v) -> "\"$k\":${num(v)}" })
            w.write("}}")
            w.newLine()
        }
    }
    return capture.frames.size
}

class FrameStats {
    var frames = 0
    var detected = 0
    var withValue = 0
    var upFlipped = 0
}

/**
 * 매니페스트 한 줄 = 캡처 하나 × 카운터 구성 하나. feature 가 비어 있으면 앱의 현재 신호를 쓴다.
 * floor·romDirection·romThreshold 는 PostureLive 가 forSession 에 넘기는 값(바닥 종목 여부, 규칙 JSON rep 설정) — 세트 로그 재생용.
 */
data class Job(
    val id: String, val capture: String, val exercise: String, val mode: String, val feature: String?, val minAmp: Float?,
    val floor: Boolean = false, val romDirection: String? = null, val romThreshold: Float? = null,
    val polarity: String? = null,
)

/**
 * 연구 재생에서 새 코어를 돌릴 수 있는 종목과 극성 — 앱 등록부는 아직 어느 종목에도 극성을 켜지 않는다(설계 §4.2).
 * 설계의 적용 대상 세 종목(무릎·팔꿈치 각을 굽혔다 편다 = 휴식이 큰 값)과, 기기 픽스처(baseline1)가 있는 푸시업류
 * (손목-어깨 거리, 팔을 편 휴식이 큰 값 — RepHysteresis KDoc 의 DOWN 예)만 적는다. 반복 중 커지는 신호(힙쓰러스트·크런치·
 * 랫풀 다운·플라이 등)를 DOWN 으로 돌리면 모든 세트가 마지막 반복을 잃으므로, 표에 없는 종목은 새 코어로 재생하지 않는다.
 */
val RESEARCH_POLARITY: Map<String, RepPolarity> = mapOf(
    "바벨 스쿼트" to RepPolarity.DOWN, "스텝 포워드 다이나믹 런지" to RepPolarity.DOWN, "덤벨 컬" to RepPolarity.DOWN,
    "푸시업" to RepPolarity.DOWN, "니푸쉬업" to RepPolarity.DOWN,
)

/** hysteresis 구성의 극성: 로그(매니페스트 10열) → 등록부 → 연구 표. 모르면 null. */
fun hysteresisPolarity(job: Job): RepPolarity? {
    job.polarity?.let { p ->
        return when (p.lowercase()) { "down" -> RepPolarity.DOWN; "up" -> RepPolarity.UP; else -> error("unknown polarity $p") }
    }
    return RepSignals.byExercise[job.exercise]?.polarity ?: RESEARCH_POLARITY[job.exercise]
}

fun counterFor(job: Job): RepCounter? {
    // 앱 세션 구성 그대로 — PostureLive 가 부르는 것과 같은 팩토리. 외부 코퍼스는 서서 하는 종목·규칙 rep 설정 없음,
    // 세트 로그는 로그의 종목 경로(바닥 여부)와 규칙 JSON 의 rep 설정을 매니페스트로 받는다.
    val session = RepCounter.forSession(job.exercise, job.romDirection, job.romThreshold, floor = job.floor) ?: return null
    val signal = if (job.feature != null) {
        // 신호 교체 진단: ROM 기준은 원래 신호의 단위라 떼어낸다(다른 피처에 붙이면 의미가 없다).
        RepSignal(job.feature, job.minAmp ?: session.signal.minAmp)
    } else session.signal
    return when (job.mode) {
        "live" -> if (job.feature == null) session else RepCounter(signal, maxGapMs = 1500L, completeOnReturn = true)
        "reversal" -> RepCounter(signal)
        // 새 코어: 극성을 아는 종목만(hysteresisPolarity). 모르면 null → run() 이 오류로 적는다.
        "hysteresis" -> hysteresisPolarity(job)?.let { RepCounter(signal.copy(polarity = it), maxGapMs = 1500L, completeOnReturn = true) }
        else -> error("unknown mode ${job.mode}")
    }
}

private val SERIES_FEATURES = listOf("knee_mean", "knee_minside", "knee_out_mean", "elbow_mean", "elbow_minside", "hip_mean")

/** 카운터 입력 한 프레임. features = null 이면 사람 없음 — 앱은 onFrame 을 부르지 않는다. */
class InputFrame(val tMs: Long, val features: Map<String, Float>?)

/** 랜드마크 캡처 → 앱 추론 후처리를 거친 입력. */
fun inputFrames(capture: Capture, stats: FrameStats): List<InputFrame> =
    capture.frames.map { InputFrame(it.tMs, frameFeatures(it, stats)) }

/** 피처 캡처(세트 로그) → 그대로. 로그의 프레임은 앱이 카운터에 넣은 프레임이라 전부 '검출' 이다. */
fun inputFrames(capture: FeatureCapture): List<InputFrame> = capture.frames.map { InputFrame(it.tMs, it.features) }

fun run(job: Job, capture: Capture, seriesDir: File?): String {
    val stats = FrameStats()
    return run(job, capture.meta, inputFrames(capture, stats), stats, seriesDir)
}

fun run(job: Job, capture: FeatureCapture, seriesDir: File?): String =
    run(job, capture.meta, inputFrames(capture), FrameStats(), seriesDir)

// 끊김 초기화 기준(RepCounter.forSession 의 maxGapMs) — 이보다 긴 틈은 일시정지·가림일 수 있다(파리티 진단용).
private const val SESSION_MAX_GAP_MS = 1_500L

fun run(job: Job, meta: Map<String, String>, frames: List<InputFrame>, stats: FrameStats, seriesDir: File?): String {
    if (job.mode == "hysteresis" && hysteresisPolarity(job) == null) {
        return json(mapOf("id" to job.id, "error" to "no polarity for ${job.exercise} (새 코어 극성 미정 — 재생하지 않음)"))
    }
    val rc = counterFor(job) ?: return json(mapOf("id" to job.id, "error" to "no counter for ${job.exercise}"))
    // 반복별 자세 검사(spec §62a) — 앱 세션 구성(live, 신호 교체 없음)에서만 앱과 같은 평가기를 나란히 돌린다. Gate A 가 이 검사의 오탐·검출을 잰다.
    val rf = if (job.mode == "live" && job.feature == null) RepFormSpecs.evaluatorFor(job.exercise, rc) else null
    var rejectedSeen = 0
    val repTimes = ArrayList<Long>()
    val cycles = ArrayList<String>()
    // ROM 판정은 셋으로 센다: 유효(true)·미달(false)·미판정(null = 그 신호에 ROM 기준이 없다).
    // 미판정을 유효에 섞으면 기준 없는 신호가 "전부 유효" 로 보인다(원칙 #1) — 앱의 '범위 미판정' 과 같은 구분.
    var valid = 0
    var invalid = 0
    var unjudged = 0
    val validSeq = ArrayList<String>()
    val series = seriesDir?.let { StringBuilder("tMs\tvalue\t" + SERIES_FEATURES.joinToString("\t") + "\n") }
    var prevT: Long? = null
    var gaps = 0
    // 카운터가 값을 실제로 본 구간(1.5 s 넘는 틈으로 끊는다) — 세트 앞뒤 헛카운트의 분당 비율 분모(run_replay.split_set)
    val spans = ArrayList<LongArray>()
    // 세트 로그의 카운터 리셋(일시정지·카메라 전환, spec §58) — 앱처럼 그 자리에서 진행 사이클(과 새 코어의 보류 사이클)을 버린다.
    // 각 리셋을 "t_ms 가 이 값 이상인 첫 프레임 앞" 의 문턱으로 바꾼다: after 가 있으면 after + 1(없음 = 첫 프레임 앞), 없으면 누른 시각.
    val resets = resetThresholds(meta).sorted()
    var nextReset = 0
    for (frame in frames) {
        while (nextReset < resets.size && resets[nextReset] <= frame.tMs) { rc.resetCycle(); nextReset++ }
        stats.frames++
        prevT?.let { if (frame.tMs - it > SESSION_MAX_GAP_MS) gaps++ }
        prevT = frame.tMs
        val features = frame.features ?: continue
        stats.detected++
        val value = signalValue(features, rc.signal.feature)
        if (value != null) {
            stats.withValue++
            val open = spans.lastOrNull()
            if (open != null && frame.tMs - open[1] <= SESSION_MAX_GAP_MS) open[1] = frame.tMs else spans += longArrayOf(frame.tMs, frame.tMs)
        }
        series?.append(frame.tMs)?.append('\t')?.append(value ?: "")?.append('\t')
            ?.append(SERIES_FEATURES.joinToString("\t") { features[it]?.toString() ?: "" })?.append('\n')
        // 반복 판별 신호(spec §62)도 앱과 같은 프레임 값으로 준다 — 없는 종목은 null(종전과 같다). 파리티가 이 인자에 기댄다.
        rf?.onFrame(frame.tMs, features)   // 앱과 같은 순서: 카운터보다 먼저
        // 앱과 같은 입구(spec §62c): 팔별 경로는 두 팔 값·기각 피처를, 그 밖은 카운트 신호 값 + 판별 신호 값을 쓴다. 파리티가 이 호출에 기댄다.
        val fired = rc.onFrameFeatures(frame.tMs, features)
        if (rc.newlyRetracted) {
            // 잠정 첫 회를 거뒀다(spec §62c 후속 9) — 앱처럼 그 회로 센 수·기록·자세 기준을 지운다(파리티)
            valid = 0; invalid = 0; unjudged = 0; validSeq.clear(); repTimes.clear(); cycles.clear()
            rf?.reset(); rejectedSeen = rc.rejectedReps.size
        }
        if (rf != null && rc.rejectedReps.size > rejectedSeen) {
            for (i in rejectedSeen until rc.rejectedReps.size) rf.onRejected(rc.rejectedReps[i].tMs)
            rejectedSeen = rc.rejectedReps.size
        }
        if (fired) {
            // 새 코어는 첫 두 사이클을 한 프레임에 함께 발표한다 — 발표된 사이클마다 한 번씩 센다(앱이 숫자를 올리는 방식).
            val published = rc.newlyPublished.ifEmpty { null }
            if (published == null) {
                val ok = if (rc.paired) rc.lastCycleValid else rc.signal.isValidRep(rc.lastCycleMin, rc.lastCycleMax)
                when (ok) { true -> valid++; false -> invalid++; null -> unjudged++ }
                validSeq += ok?.toString() ?: "null"
                repTimes += frame.tMs
                cycles += "[${num(rc.lastCycleMin)},${num(rc.lastCycleMax)},${ok ?: "null"}]"
                rf?.onCycle(frame.tMs, rc.lastCycleMin, rc.lastCycleMax)
            } else for ((k, c) in published.withIndex()) {
                rf?.onCycle(c.tMs, c.min, c.max, c.startMs)
                // 팔별 경로(spec §62c)의 회 유효는 두 팔 사이클의 ROM(월드 각 비율 + 2D 손목 보조)으로 정해진다 — 앱 RepUnit.onCounterFrame 과 같은 출처(파리티)
                val ok = if (rc.paired) rc.newlyPublishedValid.getOrNull(k) else rc.signal.isValidRep(c.min, c.max)
                when (ok) { true -> valid++; false -> invalid++; null -> unjudged++ }
                validSeq += ok?.toString() ?: "null"
                repTimes += frame.tMs
                cycles += "[${num(c.min)},${num(c.max)},${ok ?: "null"}]"
            }
        }
        // 유지 자세 사건(spec §62c 후속 6) — 앱(COACH)과 같은 자리: 이 프레임에 회가 끝나지 않았을 때만. 로그 rep_form.live 로 오탐을 잰다
        if (!fired) rf?.liveEvent(frame.tMs)
        // 처음부터 틀린 출발 알림(spec §62c 후속 9) — 앱처럼 회가 끝난 프레임에(로그 rep_form.live "…#기준")
        if (fired) rf?.takeNotice(frame.tMs)
    }
    if (series != null) File(seriesDir, "${job.id}.tsv").writeText(series.toString())
    val first = frames.firstOrNull()?.tMs
    val last = frames.lastOrNull()?.tMs
    val out = linkedMapOf<String, Any?>(
        "id" to job.id, "capture" to job.capture, "exercise" to job.exercise, "mode" to job.mode,
        "feature" to rc.signal.feature, "minAmp" to rc.signal.minAmp,
        "romDirection" to rc.signal.romDirection, "romThreshold" to rc.signal.romThreshold,
        // 카운터가 실제로 쓰는 구성(RepEngineLog.of 와 같은 출처) — 합성 픽스처가 로그 reps.config 를 앱처럼 적을 수 있게
        "romValidated" to rc.signal.romValidated, "refractoryMs" to rc.effectiveRefractoryMs,
        "maxGapMs" to rc.effectiveMaxGapMs, "completeOnReturn" to rc.effectiveCompleteOnReturn,
        "floor" to job.floor,
        "frames" to stats.frames, "detectedFrames" to stats.detected, "valueFrames" to stats.withValue,
        "upFlipped" to stats.upFlipped, "firstMs" to first, "lastMs" to last,
        "gapsOverMaxGap" to gaps, "resetsApplied" to nextReset,
        "valueSpans" to Raw(spans.joinToString(",", "[", "]") { "[${it[0]},${it[1]}]" }),
        "polarity" to rc.signal.polarity?.name?.lowercase(),
        "reps" to rc.reps, "valid" to valid, "invalid" to invalid, "romUnjudged" to unjudged,
        "repTimesMs" to Raw(repTimes.joinToString(",", "[", "]")),
        "publishedMs" to Raw(rc.repTimesMs.joinToString(",", "[", "]")),
        "cycles" to Raw(cycles.joinToString(",", "[", "]")),
        // 새 코어만: 세트 끝에 세지 않은 것(확정 대기 사이클·되돌아오던 후보)과 버려진 사이클
        "hysteresis" to rc.usesHysteresis,
        "pendingReps" to rc.pendingReps,
        "pendingMs" to rc.pendingAtSetEnd().unconfirmed?.tMs,
        "inProgress" to (rc.pendingAtSetEnd().inProgress != null),
        "droppedMs" to Raw(rc.droppedReps.joinToString(",", "[", "]") { it.tMs.toString() }),
        "publishedCycles" to Raw(rc.publishedReps.joinToString(",", "[", "]", transform = ::cycleJson)),
        // 반복 판별 게이트(spec §62) — 판별 신호가 있는 종목만 값이 있다. rejected = [t_ms, min, max, swing], identitySwing 은 센 사이클 순서(null = 미판정)
        "identityFeature" to rc.signal.identityFeature,
        "identityMinAmp" to rc.signal.identityMinAmp,
        "retracted" to Raw(rc.retractedReps.joinToString(",", "[", "]")),
        "rejected" to Raw(rc.rejectedReps.joinToString(",", "[", "]") { r -> "[${r.tMs},${num(r.min)},${num(r.max)},${num(r.identitySwing)}" + (r.feature?.let { ",\"$it\"" } ?: "") + "]" }),
        "identitySwing" to Raw(rc.identitySwings.joinToString(",", "[", "]") { it?.let(::num) ?: "null" }),
        // 팔별 경로(spec §62c) — 각 팔의 사이클(진폭·ROM 판정)과 본인 기준 진폭
        "arms" to Raw(rc.armCycles.joinToString(",", "[", "]") { "[\"${it.arm}\",${it.tMs},${it.startMs},${num(it.min)},${num(it.max)},${num(it.amp)},${it.valid?.toString() ?: "null"},${it.auxAmp?.let(::num) ?: "null"},${it.auxMin?.let(::num) ?: "null"},${it.orphan}]" }),
        "armOrphans" to rc.armOrphans,
        "armReference" to Raw("[${rc.armReference.first?.let(::num) ?: "null"},${rc.armReference.second?.let(::num) ?: "null"}]"),
        // 반복별 자세 검사(§62a) — 세트 로그 rep_form 블록과 같은 인코딩(SetLogJson.repForm), 시각은 캡처 상대 그대로
        "repForm" to rf?.let { Raw(it.summary().toLog(0L).toJson()) },
        "repFormCorrect" to rf?.summary()?.correct,
        "repFormFlags" to rf?.summary()?.flagLine(),
        "repFormLines" to rf?.summary()?.lines()?.joinToString(" | "),
    )
    out.putAll(parity(meta, rc, validSeq))
    return json(out)
}

/**
 * 세트 로그 재생의 파리티 — 캡처 메타에 앱이 로그한 카운트가 있을 때만(setlog_captures.py 가 적는다).
 *  parityCount  = 재생 카운트 == 로그 reps.count
 *  parityTimes  = 재생 repTimesMs(발화 시각) == 로그 reps.t_ms (둘 다 세트 상대시각일 때만 — 첫 로그는 절대 epoch 였다)
 *  parityValid  = 렙별 ROM 판정 순서가 로그 reps.valid 와 같다(로그에 있을 때만)
 *  signalMatchesLog = 재생 신호 == 로그 reps.signal. 다르면 파리티는 '지금 빌드' 와 '로그를 쓴 빌드' 의 차이다.
 * 없는 값은 null 로 적는다 — 판정하지 않은 것을 일치로 적지 않는다.
 */
private fun parity(meta: Map<String, String>, rc: RepCounter, validSeq: List<String>): Map<String, Any?> {
    val loggedReps = meta["loggedReps"]?.toIntOrNull() ?: return emptyMap()
    val out = linkedMapOf<String, Any?>("loggedReps" to loggedReps, "parityCount" to (rc.reps == loggedReps))
    val loggedSignal = meta["loggedSignal"]?.ifBlank { null }
    out["loggedSignal"] = loggedSignal
    out["signalMatchesLog"] = loggedSignal?.let { it == rc.signal.feature }
    val relative = meta["loggedTimesRelative"] != "false"
    val loggedTimes = meta["loggedRepTimesMs"]?.let { t -> if (t.isBlank()) emptyList() else t.split(',').mapNotNull { it.trim().toLongOrNull() } }
    out["loggedRepTimesMs"] = loggedTimes?.let { Raw(it.joinToString(",", "[", "]")) }
    out["parityTimes"] = if (loggedTimes != null && relative) loggedTimes == rc.repTimesMs.toList() else null
    val loggedValid = meta["loggedValid"]?.let { v -> if (v.isBlank()) emptyList() else v.split(',').map { it.trim() } }
    out["parityValid"] = loggedValid?.let { it == validSeq }
    // 새 코어로 센 로그(reps.engine = hysteresis_v1)만: 버려진 사이클의 발화 시각과 세트 끝 확정 대기 사이클
    meta["loggedDroppedMs"]?.let { out["parityDropped"] = longList(it) == rc.droppedReps.map { c -> c.tMs } }
    meta["loggedPendingMs"]?.let { out["parityPending"] = it.toLongOrNull() == rc.pendingAtSetEnd().unconfirmed?.tMs }
    return out
}

/**
 * 리셋 적용 문턱(프레임 t_ms 가 이 값 이상이면 그 프레임 앞에서 리셋). loggedResetsAfterMs 가 loggedResetsMs 와 같은 길이로 있으면
 * after 기준(t > after ⇔ t ≥ after + 1, "none" = 카운터가 본 프레임이 없었다 → 첫 프레임 앞), 아니면 누른 시각 기준.
 */
fun resetThresholds(meta: Map<String, String>): List<Long> {
    val pressed = longList(meta["loggedResetsMs"])
    val after = meta["loggedResetsAfterMs"]?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() }
    if (after == null || after.size != pressed.size) return pressed
    return after.map { a -> if (a == "none" || a == "null") Long.MIN_VALUE else a.toLong() + 1 }
}

private fun longList(text: String?): List<Long> =
    if (text.isNullOrBlank()) emptyList() else text.split(',').mapNotNull { it.trim().toLongOrNull() }

private fun cycleJson(c: RepCycle): String = "[${c.tMs},${c.startMs},${num(c.min)},${num(c.max)},${c.byRedescent}]"

private class Raw(val text: String)

internal fun num(v: Float): String = if (v.isFinite()) v.toString() else "null"

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
        Job(
            c[0], c[1], c[2], c[3], c.getOrNull(4)?.ifBlank { null }, c.getOrNull(5)?.ifBlank { null }?.toFloat(),
            floor = c.getOrNull(6)?.trim() == "1",
            romDirection = c.getOrNull(7)?.ifBlank { null },
            romThreshold = c.getOrNull(8)?.ifBlank { null }?.toFloat(),
            polarity = c.getOrNull(9)?.ifBlank { null },
        )
    }

/**
 * 사용법: replay <manifest.tsv> <results.jsonl> [series-dir] — 매니페스트의 캡처 경로는 매니페스트 기준 상대경로.
 * 캡처가 *.fcap 이면 피처 수준 캡처(세트 로그), 아니면 랜드마크 캡처로 읽는다.
 *        replay --dump-features <capture.cap> <out.jsonl> — 랜드마크 캡처의 프레임별 피처(재생과 같은 후처리)
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--dump-features") {
        require(args.size == 3) { "usage: replay --dump-features <capture.cap> <out.jsonl>" }
        val n = dumpFeatures(readCapture(File(args[1])), File(args[2]))
        System.err.println("dumped $n frames")
        return
    }
    require(args.size >= 2) { "usage: replay <manifest.tsv> <results.jsonl> [series-dir]\n       replay --dump-features <capture.cap> <out.jsonl>" }
    val manifest = File(args[0])
    val jobs = readManifest(manifest)
    val seriesDir = args.getOrNull(2)?.let { File(it).apply { mkdirs() } }
    val cache = HashMap<String, Capture>()
    val featureCache = HashMap<String, FeatureCapture>()
    File(args[1]).bufferedWriter().use { out ->
        for (job in jobs) {
            val path = File(job.capture).let { if (it.isAbsolute) it else File(manifest.absoluteFile.parentFile, job.capture) }
            val line = if (path.name.endsWith(".fcap")) {
                run(job, featureCache.getOrPut(path.path) { readFeatureCapture(path) }, seriesDir)
            } else {
                run(job, cache.getOrPut(path.path) { readCapture(path) }, seriesDir)
            }
            out.write(line)
            out.newLine()
        }
    }
    System.err.println("replayed ${jobs.size} jobs from ${cache.size + featureCache.size} captures")
}
