package com.example.trex_kotlin.posture

/**
 * 바닥 반복 계열 v1(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.2·§4.5·§4.6) — 크런치·라잉 레그 레이즈를 한 엔진으로 센다(사용자 결정 Q12, 2026-10-06 밤).
 *
 * 지금까지의 레거시 복귀형(크런치 `head_ground` 0.15, 레그 레이즈 중점 `hip_ang` 25°)은 머리 높이가 '고개만 까딱' 의 89 % 에서 진폭을 넘고, 3점 고관절각은 무릎을 접어도 채워졌다.
 * 여기서는 **그 종목의 주 운동 분절**로 센다 — 크런치는 어깨–골반 현이 발목→골반 선에서 든 각(`fc_trunk_lift`), 레그 레이즈는 허벅지가 어깨→골반 연장선에서 든 각(`fc_thigh`).
 * 두 신호 모두 휴식 = 낮음(0°), 작업 = 높음이다.
 *
 * @property signal 사이클 신호(`FloorChain` 피처).
 * @property depart 출발 = 기준 이후 최소점 + 이 값. 크런치 4.5° 는 `neck_only` 경계와 같고(누운 정지 잡음 p90 1.26° 의 3.6배), 레그 레이즈 20° 는 정상 진폭 p1 30° 의 2/3(잠정).
 * @property departSlack 최소점이 기준 + 이 값 안일 때만 출발한다 — 상단에서의 펄스를 사이클로 세지 않는다. 레그 레이즈 15° 는 측정 전 잠정값.
 * @property rebaseUp 기준을 위로 옮기는 상한 — 복귀 체류 중앙값이 기준 + 이 값 안일 때만.
 * @property maxCycleMs 최대 사이클 — 크런치 5 s(MM-Fit 세트 안 회 1.5~3.5 s vs 세트 밖 앉음 5.4~7.2 s), 레그 레이즈 10 s(천천히 내리기 허용, 잠정).
 * @property defaultEnabled 기본으로 켠 판별 사유(설계 §8) — **회를 지우는** 판별은 정상 회 기각이 입장선(≤ 2 %)을 넘거나 모집단이 없으면 끈다(원칙 #7 — 오탐 하나가 한 회를 지운다).
 *   **2026-10-08(폰 보고, `docs/PHONE_REPORT_2026-10-07_DESIGN.md` §3.3·§3.4)**: 꺼 두었던 사유의 위반 표본이 폰 세트에서 나왔다(한 다리 4회·무릎 접기 4회·어깨만 든 크런치) —
 *   `one_leg`(정점 두 허벅지 차 > [FloorCycleTracker.ONE_LEG_DIFF], 먼 허벅지가 없으면 무릎 간격) · `knee_bent`(상단 무릎 < 100°) · 크런치 `shallow`(10° 또는 본인 0.6배) 를 켠다.
 *   AIHub 전 클립 양다리 정점의 허벅지 차 > 35°: C 3/160 = 1.9 %·E 1.6 %, FMS 한 다리 90회 검출 100 %(`research/external_rep_replay/results/fms_aslr/`, 재생기 `--dump-features`).
 *   AIHub 엔진 재생(2026-10-07, `research/external_rep_replay/floor_replay_tables.py` — 실제 이 추적기가 나눈 회, 사유를 하나씩 켠 구성, 측면 C, 측면 판정 회 기준):
 *   크런치 `sit_up` 1/484 = 0.2 %(켬) · `shallow` 30/273 = 11.0 %(끔) / 레그 레이즈 `trunk_up` 0/306(켬) · `shallow` 3/306 = 1.0 %(켬, CI 상한 2.8 %) · `knee_bent` 3/198 = 1.5 % 이나
 *   진입·이탈 제외 2/76 = 2.6 %·CI 상한 4.4 %·수행자 최대 11~40 % 로 두 측정이 입장선을 사이에 둔다 → 끔 유지(Q10 사용자 결정) · `one_leg` 0/306 이나 위반 표본 0(끔) ·
 *   `feet_touch` 1/111 = 0.9 % 이나 '발을 바닥에 내림' 위반 검출 1.9 %(끔, Q11).
 *   크런치 `neck_only` 는 **켠다**(사용자 결정 Q6 = 4.5° 로 시작, 2026-10-06 밤). 엔진 재생에서 견갑골 충족 회의 4.5 %(13/286, CI 2.4~7.6, 수행자 최대 Z4 20 %)에 이 이유를 말했지만
 *   이 사유는 **어떤 회도 지우지 않는다** — 주 신호(현 들림)가 출발하지 않은 동작에만 나므로 그 동작은 켜든 끄든 세지 않는다. 2 % 입장선은 회를 지우는 판별의 장치라
 *   여기 쓰지 않는다. 끄면 그 동작이 틱도 이유도 없이 사라져 "카운터가 죽었나" 로 읽힌다(설계 §4.5). 4.5 % 는 '세지 않은 동작에 이유를 말한' 비율이고 폰 '목만' 블록 뒤 다시 정한다.
 */
enum class FloorProfile(val title: String, val signal: String, val depart: Float, val departSlack: Float, val rebaseUp: Float,
                        val maxCycleMs: Long, val reasons: List<String>, val defaultEnabled: Set<String>,
                        /** `shallow` 의 절대 진폭 하한(°)과 본인 처음 [FloorCycleTracker.REF_REPS]회 중앙값 대비 비율 — 둘 중 큰 쪽이 문턱(2026-10-08, 설계 §3.3·§3.4). */
                        val shallowDeg: Float, val shallowRatio: Float) {
    CRUNCH("크런치", FloorChain.TRUNK_LIFT, 4.5f, 8f, 3f, 5_000L,
        listOf("sit_up", "arms_only", "neck_only", "shallow"), setOf("sit_up", "arms_only", "neck_only", "shallow"), shallowDeg = 10f, shallowRatio = 0.6f),
    LEG_RAISE("라잉 레그 레이즈", FloorChain.THIGH, 20f, 15f, 8f, 10_000L,
        listOf("trunk_up", "knee_bent", "one_leg", "shallow", "feet_touch"), setOf("trunk_up", "knee_bent", "one_leg", "shallow"), shallowDeg = 30f, shallowRatio = 0.7f);

    /**
     * 첫 회 잠정의 확인 창 — 둘째 회가 이 안에 닫혀야 첫 회를 확정한다. 최대 사이클보다 짧으면 정상 템포의 세트도 첫 회를 거둔다(레그 레이즈 최대 10 s 인데 8 s 창이었다 —
     * 9 s 간격 세트가 회마다 앞 회를 거뒀다, 리뷰 2026-10-07). 그래서 max([FloorCycleTracker.FIRST_REP_CONFIRM_MS], 최대 사이클 + [FloorCycleTracker.FIRST_REP_SLACK_MS]):
     * 크런치 8 s, 레그 레이즈 12 s.
     */
    val firstRepConfirmMs: Long get() = maxOf(FloorCycleTracker.FIRST_REP_CONFIRM_MS, maxCycleMs + FloorCycleTracker.FIRST_REP_SLACK_MS)

    companion object { fun of(exercise: String): FloorProfile? = entries.firstOrNull { it.title == exercise } }
}

/**
 * 기각한 회의 상세 — 세트 로그 `reps.rejected[]`(설계 §4.2). 재료가 없던 값은 NaN.
 * @property trunkPeak 크런치 = 현 들림 정점(목만 당김은 탐침 동안의 최대), 레그 레이즈 = 누운 기준 대비 상체 들림 최대.
 * @property earPeak 크런치 귀 들림 정점. @property amp 사이클 신호 진폭(목만 당김은 귀 진폭). @property kneeTop 레그 레이즈 상단 무릎 중앙값. @property headMed 레그 레이즈 머리 들림 중앙값.
 * @property sideOk 정점에서 측면(`fc_yaw` ≤ 0.15)이었는가 — 기각은 측면일 때만 나므로 늘 true(유보한 회는 [FloorCycleTracker.identityAbstain]).
 */
data class FloorRejection(val tMs: Long, val reason: String, val trunkPeak: Float, val earPeak: Float, val amp: Float,
                          val kneeTop: Float, val headMed: Float, val sideOk: Boolean,
                          /** 크런치 `arms_only`(§101) 재료 — 회 창의 손목 이동(최대 쌍거리, 몸통)과 귀 들림 진폭(°). 재료가 없으면 NaN. */
                          val wristSweep: Float = Float.NaN, val earAmp: Float = Float.NaN)

/**
 * 센 회의 상세 — 로그용. [topMs] 정점 띠(진폭 상위 20 %) 안에 머문 시간, [descentMs] 그 띠를 떠나 복귀 확정까지 — 나중의 반동·툭 떨어뜨리기 후보.
 * [identityAbstain] = 정점이 측면이 아니어서 판별을 유보하고 셌다.
 */
data class FloorRep(val tMs: Long, val startMs: Long, val peakMs: Long, val min: Float, val peak: Float,
                    val topMs: Long, val descentMs: Long, val identityAbstain: Boolean,
                    /**
                     * 크런치 시선 대리(spec §100) — 상단 띠(정점 − 진폭 20 %)의 얼굴 방향(`fc_face`, 코–귀–골반 각) 중앙값 − 누운 기준의 얼굴 방향. 음수 = 얼굴이 몸통과 함께
                     * 무릎 쪽으로 돌았다(정상, AIHub C 416클립 중앙값 −34°), 0 근처·양수 = 머리가 뒤에 남아 천장을 본다(AIHub p95 −0.9·p98 +5.9°). 재료가 없으면 NaN.
                     */
                    val faceRel: Float = Float.NaN,
                    /**
                     * 화면(폰)을 본 정도(§101c, 사용자 정의 시선 — "화면을 보면 2회까지는 두고 그 뒤 교정 멘트, 횟수는 막지 않음"): 사이클 프레임의 `fc_face_cam`(두 귀 간격 ÷ 코–귀 거리) 중앙값.
                     * 옆모습(천장·무릎을 봄) ≤ 0.3, 얼굴이 폰을 향하면 ≥ 1.0. 재료가 없으면 NaN. 크런치·레그 레이즈 공통([FloorGazeVoice]).
                     */
                    val faceCam: Float = Float.NaN,
                    /** 시선 상태(§101d, `FloorGaze`): 사이클 프레임의 다수결 — 0 모름 · 1 정면 · 2 화면 쪽 · 3 반대쪽 · 4 화면 밖(미검출 = 카메라를 향하지 않음). 얼굴 모델 피처가 없는 로그(옛 빌드·.cap 재생)는 0. */
                    val gaze: Int = FloorGaze.UNKNOWN)

/** 소리 없이 버린 후보·기준 — `timeout`(최대 사이클 초과)·`exit`(일어나 앉음). 세트 끝 동작이라 틱도 이유도 내지 않는다. */
data class FloorDiscard(val tMs: Long, val reason: String)

/**
 * 바닥 반복 추적기 — `LegCycleTracker` 의 틀(기준 → 출발 → 복귀 체류 → 판별 → 발표/기각 → 재기준)을 **복제**했다. 일반화하지 않은 것은 `LegCycleTracker` 가
 * 서서 하는 `REQUIRED` 키와 `LegProfile` 에 묶여 있고 v3 가 테스트로 잠겼기 때문이다 — 상수 이름을 같게 두어 나중에 합친다.
 *
 * - **누운 기준(정지 요구 없음)**: 누운 영역(중력 대비 어깨→골반 축 ≤ [LYING_AXIS_MAX], 레그 레이즈는 허벅지 ≤ [LEG_LYING_THIGH_MAX]도) 프레임이 최근 [LYING_WINDOW_MS] 에
 *   [LYING_MIN_FRAMES] 이상이면 그 신호 중앙값. 아래로는 언제든, 위로는 복귀 체류 중앙값이 [FloorProfile.rebaseUp] 안일 때만. 정지나 몸 내재 절대각 기준은 쉬지 않는 사용자
 *   (MM-Fit w06·w14 — 세트 안 현각 최소 17~21°, 1 s 정지 창 0개)를 0회로 만들었다(설계 §3.2). up 을 믿을 수 없어 축각이 없으면 누운 영역 게이트 없이 진폭·최대 사이클·출구로만 막는다.
 * - **출발**: 기준 이후 최소점 + [FloorProfile.depart](최소점이 기준 + [FloorProfile.departSlack] 안일 때만). **복귀**: 신호 ≤ 정점 − [RETURN_FRACTION] × (정점 − 최소점)이
 *   [DWELL_MS]·[DWELL_FRAMES] — 완전히 안 내려와도(긴장 유지) 회 사이 바닥점에서 닫힌다(MM-Fit w20: 회 사이 바닥이 기준보다 3.3~4.5° 위라 고정 띠로는 닫히지 않았다).
 * - **최소/최대 사이클** [MIN_CYCLE_MS] / [FloorProfile.maxCycleMs](넘으면 `timeout`). **공백** [MAX_GAP_MS] 넘으면 진행 후보만 버린다(기준 유지).
 * - **출구**: 크런치 현 들림 > [CRUNCH_EXIT_DEG], 레그 레이즈 상체 들림 > [LEG_EXIT_TILT] 가 [EXIT_MS] 이어지면 후보를 버리고 기준을 무효로 한다 — 세트 끝 일어나기를 회로 세지 않는다(§31a).
 *   진행 후보에 판별 사유가 있었으면(느린 상단의 윗몸일으키기는 출구가 판별보다 먼저 걸린다 — MM-Fit w14·w19) 보류했다가 [EXIT_RETURN_MS] 안에 누운 쪽으로 돌아오면
 *   그 사유로 기각하고 말한다. 안 돌아오면 세트 끝 일어남이라 소리 없이 버린다. 기준을 잃은 뒤 [NO_BASE_CUE_MS] 동안 다시 못 잡으면 누운 기준 안내를 한 번 더 한다.
 * - **첫 회 잠정**: 세트의 첫 회는 [FloorProfile.firstRepConfirmMs] 안에 둘째 회가 없으면 거둔다(`RepCounter` 팔별 경로 선례 — 세트 시작 헛사이클). **세트에 한 번만** 건다 —
 *   거둔 뒤의 회는 잠정이 아니다(전에는 거둔 뒤 다음 회가 다시 '첫 회' 가 되어 느린 세트가 회마다 앞 회를 거뒀다). 세지 않은 동작(기각)도 세트가 시작됐다는 증거라 잠정을
 *   확정한다. 일시정지·카메라 전환([resetCycle])도 확정한다 — 쉬는 동안 거두지 않는다(`RepCounter.resetCycle` 과 같은 결정).
 * - **판별**(원칙 #7 — 횟수의 입장 조건, 두 모드 같음): 복귀 확정 때 [FloorProfile.reasons] 순서대로 묻고 첫 사유만 기록한다(순서 = 음성 우선순위). 재료가 없는 조건은 통과,
 *   꺼진 사유([enabled] 밖)도 통과. 판별 띠는 AIHub 측면(C)에서만 쟀으므로 **정점의 `fc_yaw` 가 [SIDE_YAW_MAX] 이하일 때만** 판정하고, 아니면 판별 전체를 유보하고 센다([identityAbstain]).
 * - **크런치 목만 당김**: 귀 들림(`fc_ear_lift`)의 별도 1차원 사이클(탐침, 출발 + [PROBE_DEPART])이 닫혔는데 그동안 주 신호가 출발하지 않았으면 `neck_only` 로 기각한다 —
 *   그래야 목만 당긴 동작도 '이유를 말하는 회' 가 된다(침묵하면 카운트가 죽은 줄 안다). 4.5° 경계는 '고개만' 의 약 17 % 만 거른다(설계 Q6). **기본 켬**(Q6) — 회를 지우지
 *   않는 사유라 2 % 입장선 대상이 아니다([FloorProfile] KDoc).
 * - **신호 결측 안내**: 사이클 신호가 [SIGNAL_LOST_MS] 끊기면 **빠진 관절**을 말한다 — 크런치 현 들림은 발목(접지선), 레그 레이즈 허벅지는 무릎이 있어야 계산된다.
 *   어깨·골반이 보이는데 "어깨와 골반이 보이게" 라고 하면 사용자가 엉뚱한 곳을 고친다(원칙 #5).
 * - 띠는 전부 **잠정**(AIHub 16프레임 클립의 분할 의존 수치·MM-Fit 5명) — 엔진 재생과 폰 세션으로 확정한다(설계 §7).
 */
class FloorCycleTracker(val profile: FloorProfile, enabled: Set<String> = profile.defaultEnabled) : IdentityCueSource {
    /** 켠 판별 사유. 여기 없는 사유는 조건을 만족해도 센다. */
    val enabled: Set<String> = enabled.toSet()

    private class LyingFrame(val t: Long, val sig: Float, val torso: Float?, val ear: Float?, val leg: Float?, val face: Float?)
    private val lyingWin = ArrayDeque<LyingFrame>()
    private var base: Float? = null
    private var torsoBase: Float? = null
    private var earBase: Float? = null
    private var legBase: Float? = null           // 세트 첫 누운 다리각(발 닿음 판별)
    private var faceBase: Float? = null          // 누운 얼굴 방향(크런치 시선 대리 §100) — 기준과 함께 잡고 재기준에 따라간다
    private val faces = ArrayList<Pair<Float, Float>>()   // (신호, 얼굴 방향) — 상단 띠 중앙값(크런치)
    private val faceCams = ArrayList<Float>()             // 사이클의 `fc_face_cam`(화면을 본 정도, §101c) — 회의 중앙값
    private val gazes = ArrayList<Int>()                   // 사이클 프레임의 시선 상태 — 회의 다수결
    private var frameGaze = FloorGaze.UNKNOWN

    // ---- 주 사이클
    private var floorMin = Float.POSITIVE_INFINITY
    private var moving = false
    private var startMs = 0L
    private var startMin = 0f
    private var peak = Float.NEGATIVE_INFINITY
    private var peakMs = 0L
    private var peakYaw: Float? = null
    private var peakRatio: Float? = null
    private var earPeak = Float.NEGATIVE_INFINITY
    private var torsoTiltMax = Float.NEGATIVE_INFINITY
    private var kneeGapPeak: Float? = null
    private var peakDiff: Float? = null          // 정점 프레임의 두 허벅지 차(먼 허벅지가 보일 때) — one_leg
    private var startYaw: Float? = null          // 출발 프레임(누운, 몸이 펴진)의 측면도·비율 — 정점에서는 몸이 접혀 fc_ratio 가 무너진다
    private var startRatio: Float? = null
    private var raisedFrames = 0                 // 출발선 위에 있던 판정 칸 수 — 한 칸짜리 튐은 회가 아니다
    private val countedAmps = ArrayList<Float>() // 센 회의 진폭(처음 REF_REPS 회가 본인 기준)
    private val topKnees = ArrayList<Pair<Float, Float>>()      // (신호, 무릎각) — 레그 레이즈 상단 무릎
    private val heads = ArrayList<Float>()
    private val cycleFrames = ArrayList<Pair<Long, Float>>()
    private var returnAt: Long? = null
    private val dwell = ArrayList<Float>()
    private var fromRest = true                  // 이 사이클이 기준을 잡은 뒤 첫 사이클(휴식에서 출발)인가 — 발 닿음은 회 사이 바닥에만 묻는다
    private var legMinIdle = Float.POSITIVE_INFINITY
    private var legTrough = Float.POSITIVE_INFINITY

    private var exitSince: Long? = null
    private var lastAt: Long? = null
    private var lastMainDepartAt = Long.MIN_VALUE / 2
    private var lastMainEndAt = Long.MIN_VALUE / 2

    // ---- 크런치 팔만 움직임(§101 `arms_only`) — 손목 몸통 좌표의 최근 버퍼(사이클과 무관하게 쌓고 시간으로 자른다), 출발 직전 귀 들림(진폭의 원점)
    private val wrists = ArrayList<Triple<Long, Float, Float>>()
    private var earIdleMin = Float.POSITIVE_INFINITY
    private var earTrough = Float.POSITIVE_INFINITY
    /** 팔 자세(§101 RC3): 팔꿈치각 < [ARM_FOLDED_DEG] 접음(손 머리 뒤), > [ARM_EXTENDED_DEG] 폄(손 뻗기). 자세가 바뀌면 골(`floorMin`)을 다시 잡는다 —
     *  팔을 옮기는 프레임의 어깨점 이동이 골이 되어(10-08 48357 회 −8.2°) 진폭의 원점을 낮췄다. 모르면 직전 자세 유지. */
    private var armPose = 0
    // ---- 크런치 귀 탐침(목만 당김)
    private var earMin = Float.POSITIVE_INFINITY
    private var probeMoving = false
    private var probeStart = 0L
    private var probeStartMin = 0f
    private var probePeak = Float.NEGATIVE_INFINITY
    private var probePeakYaw: Float? = null
    private var probePeakRatio: Float? = null
    private var probeMainMax = Float.NEGATIVE_INFINITY
    private var probeReturnAt: Long? = null
    private val probeDwell = ArrayList<Float>()
    private var probeLastAt: Long? = null

    // ---- 첫 회 잠정·안내
    private var tentativeAt: Long? = null
    private var tentativeUsed = false            // 세트에 한 번 — 첫 회를 잠정으로 걸었다
    private var firstSeenAt: Long? = null
    private var lastSignalAt: Long? = null
    private var lastMissing: String? = null      // 마지막으로 신호가 빠진 프레임에서 빠진 관절([MISSING_TORSO] 등). 신호가 있으면 null
    private var noBaseCued = false
    private var lostCuedAt = Long.MIN_VALUE / 2
    private var baseLostAt: Long? = null         // 출구로 기준을 잃은 시각(다시 잡으면 null)
    private var baseLostCued = false

    // ---- 출구가 먼저 걸린 판별 후보(느린 상단) — 곧 누운 쪽으로 돌아오면 그 사유로 기각한다
    private class ExitPending(val t: Long, val reason: String, val startMin: Float, val peak: Float, val thr: Float,
                              val trunkPeak: Float, val earPeak: Float, val amp: Float, val kneeTop: Float, val headMed: Float)
    private var exitPending: ExitPending? = null

    /** 센 회(발표 순서). 거둔 첫 회는 빠진다. */
    val completed = ArrayList<RepCycle>()
    /** 판별이 세지 않은 회 — `RepRejected.feature` = 사유. 틱과 이유 음성의 원천(`RepCounter.rejectedReps`). */
    val rejected = ArrayList<RepRejected>()
    val rejectedDetail = ArrayList<FloorRejection>()
    val repDetail = ArrayList<FloorRep>()
    val discarded = ArrayList<FloorDiscard>()
    /** 판별을 유보하고 센 회의 시각(정점이 측면이 아님) — 로그 `identity_abstain`. */
    val identityAbstain = ArrayList<Long>()
    /** 거둔 첫 회의 시각. */
    val retracted = ArrayList<Long>()
    /** 직전 [onFrame] 에서 첫 회를 거뒀다. */
    var newlyRetracted = false
        private set
    /** 세트 로그 `reps.config.lying` — 세트 처음 잡힌 누운 기준(재기준 전). 재생기가 같은 값을 심는다([restoreLying]). */
    var lying: Map<String, Float>? = null
        private set
    val pending: Boolean get() = moving
    val hasBase: Boolean get() = base != null
    /** 지금 기준(로그·진단용). */
    val baseValue: Float? get() = base
    fun candidate(): RepCandidate? = if (moving) RepCandidate(startMs, startMin, peak) else null

    override fun cueFor(reason: String): String? = Companion.cueFor(profile, reason)
    override fun motionFor(reason: String): FormMotion? = Companion.motionFor(profile, reason)

    /**
     * 일시정지·카메라 전환 — 진행 후보(와 탐침·출구 보류)만 버린다. 기준·완료 원장은 지킨다. 잠정 첫 회는 **확정**한다 — 쉬는 동안(폰 위치를 고치는 동안) 거두지 않는다
     * (`RepCounter.resetCycle` 의 `tentativeFirstAt = null` 과 같은 결정. 전에는 바닥 경로의 잠정이 추적기 안에 있어 이 보호가 빠졌다 — 재개 첫 프레임에서 거뒀다).
     */
    fun resetCycle() {
        clearCycle(); dropProbe(); floorMin = Float.POSITIVE_INFINITY; earMin = Float.POSITIVE_INFINITY; lastAt = null; exitSince = null; probeLastAt = null
        wrists.clear(); earIdleMin = Float.POSITIVE_INFINITY; earTrough = Float.POSITIVE_INFINITY; armPose = 0
        tentativeAt = null; exitPending = null
    }

    fun reset() {
        resetCycle(); lyingWin.clear(); base = null; torsoBase = null; earBase = null; legBase = null; faceBase = null; lying = null
        completed.clear(); rejected.clear(); rejectedDetail.clear(); repDetail.clear(); discarded.clear(); identityAbstain.clear(); retracted.clear(); countedAmps.clear()
        newlyRetracted = false; tentativeAt = null; tentativeUsed = false; firstSeenAt = null; lastSignalAt = null; lastMissing = null
        noBaseCued = false; lostCuedAt = Long.MIN_VALUE / 2; baseLostAt = null; baseLostCued = false
        lastMainDepartAt = Long.MIN_VALUE / 2; lastMainEndAt = Long.MIN_VALUE / 2; fromRest = true; legMinIdle = Float.POSITIVE_INFINITY
    }

    /**
     * 준비 카운트다운 프레임([atMs] 앞 [PREP_WINDOW_MS])이 **누운 영역**이면 기준을 심는다(`RepCounter.standingSeedFrom` 입구). 축각이 없거나(up 미확인)
     * 한 프레임이라도 누운 영역이 아니면(앉아 있음) 심지 않는다 — 세트 중 스스로 잡는다.
     */
    fun prepare(frames: List<Pair<Long, Map<String, Float>>>, atMs: Long) {
        if (base != null) return
        val fs = frames.filter { atMs - it.first in 0..PREP_WINDOW_MS }
            .mapNotNull { (t, m) -> cycleSignal(m)?.let { Triple(t, it, m) } }
        if (fs.size < LYING_MIN_FRAMES) return
        if (fs.any { (_, x, m) -> m[FloorChain.AXIS_H]?.isFinite() != true || !lyingRegion(m, x) }) return
        establish(fs.map { it.second }, fs.mapNotNull { it.third[FloorChain.TORSO_ELEV] }, fs.mapNotNull { it.third[FloorChain.EAR_LIFT] },
            fs.mapNotNull { it.third[FloorChain.LEG] }, fs.mapNotNull { it.third[FloorChain.FACE] }, fromPrep = true)
    }

    /**
     * 로그의 누운 기준(`reps.config.lying`)을 심는다(재생 파리티) — 앱이 **준비 프레임**에서 심은 기준([LYING_PREP] = 1)만. 세트 중에 잡은 기준([LYING_PREP] = 0)은 심지 않는다 —
     * 재생기도 같은 프레임에서 스스로 잡는다(첫 프레임에 심으면 앉아서 시작한 세트에서 앱보다 일찍 기준을 가져 출구 기록이 달라진다). [LYING_PREP] 이 없는 옛 로그는 준비로 본다.
     * 상태는 [prepare] → establish 와 같게 만든다 — 출발 최소점은 준비 프레임의 최솟값([LYING_MIN], 없으면 기준).
     */
    fun restoreLying(m: Map<String, Float>) {
        if (m[LYING_PREP] == 0f) return
        val b = m[profile.signal]?.takeIf { it.isFinite() } ?: return
        base = b; floorMin = minOf(floorMin, m[LYING_MIN]?.takeIf { it.isFinite() } ?: b)
        fromRest = true; legMinIdle = Float.POSITIVE_INFINITY; baseLostAt = null
        torsoBase = m[FloorChain.TORSO_ELEV]?.takeIf { it.isFinite() }
        earBase = m[FloorChain.EAR_LIFT]?.takeIf { it.isFinite() }
        faceBase = m[FloorChain.FACE]?.takeIf { it.isFinite() }
        if (legBase == null) legBase = m[FloorChain.LEG]?.takeIf { it.isFinite() }
        if (lying == null) lying = m.filterValues { it.isFinite() }
    }

    /** 누운 기준 대비 상체 들림(`fc_torso_tilt`)을 더한다 — 기준·축각이 없으면 그대로. 앱·재생기가 같은 자리에서 부른다(로그 피처). */
    fun annotate(features: Map<String, Float>): Map<String, Float> {
        val tb = torsoBase ?: return features
        val e = features[FloorChain.TORSO_ELEV]?.takeIf { it.isFinite() } ?: return features
        return features + (FloorChain.TORSO_TILT to e - tb)
    }

    /**
     * 사이클 신호. 레그 레이즈는 두 허벅지 중 **더 올라간 쪽**(먼 허벅지가 보일 때, 2026-10-08) — MediaPipe 가 든 다리의 좌우 이름을 프레임마다 바꿔 붙여서
     * 가까운 쪽 허벅지만 보면 다리를 든 채 가만히 있어도 신호가 90° ↔ −8° 를 오가며 사이클이 났다(10-08 폰 세트 12회 중 7회가 그 유령 회). 더 올라간 쪽은
     * 이름이 바뀌어도 같은 값이다. 한 다리만 들면 이 신호가 그 다리를 따라가고 [identity] 의 `one_leg` 가 거른다(먼 다리만 든 동작도 무음이 아니라 그 사유로 말한다).
     */
    private fun cycleSignal(m: Map<String, Float>): Float? {
        val x = m[profile.signal]?.takeIf { it.isFinite() } ?: return null
        if (profile != FloorProfile.LEG_RAISE) return x
        val far = m[FloorChain.THIGH_FAR]?.takeIf { it.isFinite() } ?: return x
        return maxOf(x, far)
    }

    fun onFrame(t: Long, input: Map<String, Float>): List<RepCycle> {
        newlyRetracted = false
        if (firstSeenAt == null) firstSeenAt = t
        tentativeAt?.let { t0 -> if (t - t0 > profile.firstRepConfirmMs) { if (completed.size == 1) retractFirst(); tentativeAt = null } }
        fun g(k: String): Float? = input[k]?.takeIf { it.isFinite() }
        frameGaze = FloorGaze.classify(input)              // 매 프레임의 시선 상태(§101d) — 사이클 프레임만 회의 다수결에 들어간다
        val x = cycleSignal(input)
        if (x == null) {
            // 무엇이 빠졌나(안내 문장) — 관측 층은 몸통(어깨·골반)이 안 보이면 fc_* 를 내지 않는다. 몸통이 있으면 크런치는 발목, 레그 레이즈는 무릎이 빠진 것
            lastMissing = when { input[FloorChain.TORSO] == null -> MISSING_TORSO; profile == FloorProfile.CRUNCH -> MISSING_ANKLE; else -> MISSING_KNEE }
            return emptyList()
        }
        lastSignalAt = t; lastMissing = null
        val last = lastAt
        if (last != null && (t <= last || t - last > MAX_GAP_MS)) { clearCycle(); dropProbe(); floorMin = Float.POSITIVE_INFINITY; earMin = Float.POSITIVE_INFINITY; exitSince = null }
        lastAt = t
        // 출구가 먼저 걸린 판별 후보 — 곧 누운 쪽으로 돌아왔으면 그 사유로 기각(세트 끝 일어남은 돌아오지 않는다)
        exitPending?.let { p ->
            if (t - p.t > EXIT_RETURN_MS) exitPending = null
            else if (x <= p.thr) {
                exitPending = null
                reject(t, p.reason, p.startMin, p.peak, FloorRejection(t, p.reason, p.trunkPeak, p.earPeak, p.amp, p.kneeTop, p.headMed, sideOk = true))
            }
        }
        val tilt = torsoBase?.let { tb -> g(FloorChain.TORSO_ELEV)?.let { it - tb } }
        // 출구 — 세트 끝에 일어나 앉음
        val exiting = when (profile) {
            FloorProfile.CRUNCH -> x > CRUNCH_EXIT_DEG
            FloorProfile.LEG_RAISE -> tilt != null && tilt > LEG_EXIT_TILT
        }
        if (exiting) {
            val s = exitSince ?: t.also { exitSince = it }
            if (t - s >= EXIT_MS) { exit(t); return emptyList() }
        } else exitSince = null
        if (!moving && lyingRegion(input, x)) addLying(t, x, g(FloorChain.TORSO_ELEV), g(FloorChain.EAR_LIFT), g(FloorChain.LEG), g(FloorChain.FACE))
        if (profile == FloorProfile.CRUNCH) {
            val al = g(FloorChain.WRIST_AL); val up = g(FloorChain.WRIST_UP)
            if (al != null && up != null) { wrists += Triple(t, al, up); wrists.removeAll { it.first < t - WRIST_KEEP_MS } }
            // 팔 자세가 바뀌면(접음 ↔ 폄) 쉬는 동안의 골을 다시 잡는다 — 팔을 옮기던 프레임의 어깨점 이동은 크런치의 원점이 아니다(§101 RC3)
            g(FloorChain.ELBOW)?.let { e ->
                val pose = if (e < ARM_FOLDED_DEG) 1 else if (e > ARM_EXTENDED_DEG) 2 else armPose
                if (pose != armPose) { if (!moving && armPose != 0) floorMin = Float.POSITIVE_INFINITY; armPose = pose }
            }
        }
        val b = base ?: return emptyList()
        val events = mainStep(t, x, b, tilt, ::g)
        if (profile == FloorProfile.CRUNCH) probeStep(t, x, ::g)
        return events
    }

    private fun mainStep(t: Long, x: Float, b: Float, tilt: Float?, g: (String) -> Float?): List<RepCycle> {
        if (!moving) {
            if (x < floorMin) floorMin = x
            g(FloorChain.LEG)?.let { legMinIdle = minOf(legMinIdle, it) }
            g(FloorChain.EAR_LIFT)?.let { earIdleMin = minOf(earIdleMin, it) }
            if (!(x >= floorMin + profile.depart && floorMin <= b + profile.departSlack)) return emptyList()
            // 출발 — 측면도·비율은 몸이 펴진 이 프레임의 것(정점에서는 몸이 접혀 어깨–발목 ÷ 어깨폭이 8 아래로 무너진다: 10-08 폰 레그 레이즈 정점 7~9)
            clearCycle(); moving = true; startMs = t; startMin = floorMin; lastMainDepartAt = t
            startYaw = g(FloorChain.YAW); startRatio = g(FloorChain.RATIO)
            legTrough = legMinIdle; legMinIdle = Float.POSITIVE_INFINITY
            earTrough = earIdleMin; earIdleMin = Float.POSITIVE_INFINITY
        }
        cycleFrames += t to x
        if (x >= startMin + profile.depart) raisedFrames++
        if (x > peak) {
            peak = x; peakMs = t; peakYaw = g(FloorChain.YAW); peakRatio = g(FloorChain.RATIO); kneeGapPeak = g(FloorChain.KNEE_GAP)
            peakDiff = g(FloorChain.THIGH)?.let { n -> g(FloorChain.THIGH_FAR)?.let { f -> kotlin.math.abs(n - f) } }
        }
        g(FloorChain.EAR_LIFT)?.let { earPeak = maxOf(earPeak, it) }
        tilt?.let { torsoTiltMax = maxOf(torsoTiltMax, it) }
        if (profile == FloorProfile.LEG_RAISE) { g(FloorChain.KNEE)?.let { topKnees += x to it }; g(FloorChain.HEAD_LIFT)?.let { heads += it } }
        if (profile == FloorProfile.CRUNCH) g(FloorChain.FACE)?.let { faces += x to it }
        g(FloorChain.FACE_CAM)?.let { faceCams += it }
        gazes += frameGaze
        if (t - startMs > profile.maxCycleMs) {
            discarded += FloorDiscard(t, "timeout"); lastMainEndAt = t
            clearCycle(); floorMin = x; return emptyList()
        }
        val thr = peak - RETURN_FRACTION * (peak - startMin)
        if (x > thr) { returnAt = null; dwell.clear(); return emptyList() }
        if (returnAt == null) returnAt = t
        dwell += x
        if (t - returnAt!! < DWELL_MS || dwell.size < DWELL_FRAMES || t - startMs < MIN_CYCLE_MS) return emptyList()
        return close(t, b)
    }

    /** 복귀 확정 — 판별 → 발표/기각, 재기준. */
    private fun close(t: Long, b: Float): List<RepCycle> {
        val amp = peak - startMin
        lastMainEndAt = t
        // 한 칸짜리 튐(관절이 한 프레임 튀었다 돌아옴)은 회가 아니다 — 소리 없이 버린다(10-08 폰: 가만히 있어도 횟수가 늘었다)
        if (raisedFrames < MIN_RAISED_FRAMES) {
            discarded += FloorDiscard(t, DISCARD_SPIKE)
            floorMin = dwell.min(); clearCycle()
            return emptyList()
        }
        val sideOk = sideLoose()
        val reason = if (sideOk) identity(amp, strict = sideStrict()) else null
        val out = ArrayList<RepCycle>(1)
        if (reason != null) {
            reject(t, reason, startMin, peak, FloorRejection(t, reason,
                trunkPeak = if (profile == FloorProfile.CRUNCH) peak else finiteOr(torsoTiltMax),
                earPeak = if (profile == FloorProfile.CRUNCH) finiteOr(earPeak) else Float.NaN,
                amp = amp, kneeTop = kneeTop(), headMed = if (heads.isEmpty()) Float.NaN else median(heads), sideOk = true,
                wristSweep = wristSweep(t), earAmp = earAmp()))
        } else {
            if (!sideOk) identityAbstain += t
            val c = RepCycle(t, startMs, startMin, peak)
            completed += c; out += c; countedAmps += amp
            val band = peak - TOP_BAND_FRACTION * amp
            val top = cycleFrames.filter { it.second >= band }
            val topStart = top.firstOrNull()?.first ?: peakMs; val topEnd = top.lastOrNull()?.first ?: peakMs
            // 크런치 시선 대리(§100): 상단 띠 얼굴 방향 중앙값 − 누운 기준. 재료(코·귀 가시성, 누운 얼굴)가 없으면 NaN(유보)
            val faceTop = faces.filter { it.first >= band }.map { it.second }.takeIf { it.isNotEmpty() }?.let(::median)
            val faceRel = if (faceTop != null && faceBase != null) faceTop - faceBase!! else Float.NaN
            // 화면을 본 정도(§101c): 사이클 프레임 전체의 중앙값 — 회의 어느 순간이든 폰을 향한 얼굴이 절반 넘게 이어졌는가
            val faceCam = faceCams.takeIf { it.isNotEmpty() }?.let(::median) ?: Float.NaN
            repDetail += FloorRep(t, startMs, peakMs, startMin, peak, topEnd - topStart, t - topEnd, identityAbstain = !sideOk, faceRel = faceRel, faceCam = faceCam,
                gaze = FloorGaze.majority(gazes))
            // 세트의 첫 회만 잠정 — 거둔 뒤의 회·둘째 회는 아니다(둘째 회가 오면 첫 회 확정)
            tentativeAt = if (!tentativeUsed) { tentativeUsed = true; t } else null
        }
        val med = median(dwell)
        if (med > b && med - b <= profile.rebaseUp) base = med
        floorMin = dwell.min()
        fromRest = false
        clearCycle()
        return out
    }

    /**
     * 측면인가 — `fc_yaw` ≤ [maxYaw] 이고, `fc_ratio` 가 있으면 ≥ [SIDE_RATIO_MIN]. 아니면 판별 전체를 유보하고 센다.
     * 측면도는 **출발 프레임**(누워 몸이 펴진)의 것([sideYaw]·[sideRatio]) — 정점에서는 몸이 접혀 비율이 무너진다(10-08 폰 레그 레이즈 12회 전부 유보).
     * 절대각 사유(`sit_up`)는 [SIDE_YAW_MAX], 상대·비율 사유(진폭·두 허벅지 차·무릎)는 [SIDE_YAW_MAX_RELATIVE] — 이 폰 사용자의 자연스러운 배치가 yaw 0.16~0.23 이라
     * 0.15 로는 판별이 한 번도 돌지 않았다(10-07 크런치 12/15 유보).
     */
    private fun isSide(yaw: Float?, ratio: Float?, maxYaw: Float): Boolean = yaw != null && yaw <= maxYaw && (ratio == null || ratio >= SIDE_RATIO_MIN)
    private fun sideYaw(): Float? = startYaw ?: peakYaw
    /** 출발 프레임에 측면도가 있었으면 비율도 그 프레임의 것(없으면 묻지 않음) — 정점 비율로 되돌아가면 접힌 몸이 유보를 만든다. */
    private fun sideRatio(): Float? = if (startYaw != null) startRatio else peakRatio
    /** 상대·비율 사유의 측면 — 측면도만 본다(비율 ≥ 8 은 절대각 사유의 투영 조건: 이 폰 사용자는 누운 출발 프레임에서도 비율 6.5~8.8 이라 10-07 크런치 2회가 유보됐다). */
    private fun sideLoose(): Boolean = sideYaw()?.let { it <= SIDE_YAW_MAX_RELATIVE } == true
    private fun sideStrict(): Boolean = isSide(sideYaw(), sideRatio(), SIDE_YAW_MAX)

    /** 본인 기준 진폭 — 센 회가 [REF_REPS] 이상이면 처음 [REF_REPS] 회의 중앙값(컬 ROM §62c 와 같은 틀). 그 전엔 null(절대 하한만). */
    private fun refAmp(): Float? = if (countedAmps.size >= REF_REPS) median(countedAmps.take(REF_REPS)) else null

    /**
     * 판별 — 기각 사유, null = 센다. 순서 = 사유의 우선순위(첫 사유만 말한다). 재료가 없는 조건·꺼진 사유는 통과한다(원칙 #7 '유보는 통과').
     * 크런치의 `neck_only` 는 주 사이클이 아니라 귀 탐침이 낸다([probeStep]). [strict] = 절대각 사유를 판정할 만큼 측면인가(`sit_up`).
     * `shallow` 의 문턱 = max(절대 하한, 본인 처음 3회 중앙값 × 비율) — 세트 중반에 덜 하는 회(10-07 크런치 "중반부에는 덜 했지만")를 거른다.
     */
    private fun identity(amp: Float, strict: Boolean): String? {
        fun on(r: String) = r in enabled
        val shallowThr = maxOf(profile.shallowDeg, (refAmp() ?: 0f) * profile.shallowRatio)
        return when (profile) {
            FloorProfile.CRUNCH -> when {
                on("sit_up") && strict && peak > SIT_UP_DEG -> "sit_up"
                // 팔만 움직임(§101): 회 창에서 손목이 크게 옮겨 다녔는데(≥ 0.8 몸통) 귀는 거의 들리지 않았다(< 10°) — 손을 머리 위로 넘기거나 바닥을 짚는 동작이 어깨점을 끌어 주 신호가 났다.
                // 모집단 정상 회 0/291(AIHub C 0/213·MM-Fit 0/78, 상한 1.3 %), 폰 팔만 동작 5/5, 실제 회 0/49. 손목 재료가 2칸 미만이면 유보(통과)
                on("arms_only") && wristSweep(lastAt ?: 0L).let { it.isFinite() && it > ARMS_ONLY_SWEEP } && earAmp().let { it.isFinite() && it < ARMS_ONLY_EAR_DEG } -> "arms_only"
                on("shallow") && amp < shallowThr -> "shallow"
                else -> null
            }
            FloorProfile.LEG_RAISE -> when {
                on("trunk_up") && torsoTiltMax.isFinite() && torsoTiltMax > LEG_TRUNK_UP_DEG -> "trunk_up"
                on("knee_bent") && kneeTop().let { it.isFinite() && it < LEG_KNEE_BENT_DEG } -> "knee_bent"
                // 한 다리: 정점에서 두 허벅지 차(먼 허벅지가 보일 때), 아니면 두 무릎 간격. 둘 다 없으면(먼 다리 가림) 판정하지 않는다
                on("one_leg") && (peakDiff?.let { it > ONE_LEG_DIFF } ?: (kneeGapPeak?.let { it > ONE_LEG_GAP } ?: false)) -> "one_leg"
                on("shallow") && amp < shallowThr -> "shallow"
                // 회 사이 바닥에서 발이 닿았는가 — 기준을 잡은 뒤 첫 사이클(휴식에서 출발)은 묻지 않는다
                on("feet_touch") && !fromRest && legBase != null && legTrough.isFinite() && legTrough <= legBase!! + FEET_TOUCH_MARGIN -> "feet_touch"
                else -> null
            }
        }
    }

    /** 회 창(출발 [ARMS_WINDOW_LEAD_MS] 전 ~ [now])의 손목 이동 — 몸통 좌표 점들의 최대 쌍거리(몸통 단위). 점이 2개 미만이면 NaN. */
    private fun wristSweep(now: Long): Float {
        val pts = wrists.filter { it.first >= startMs - ARMS_WINDOW_LEAD_MS && it.first <= now }
        if (pts.size < 2) return Float.NaN
        var best = 0f
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val dx = pts[i].second - pts[j].second; val dy = pts[i].third - pts[j].third
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d > best) best = d
        }
        return best
    }

    /** 이 회의 귀 들림 진폭 — 정점 − 출발 직전 쉬던 최소. 재료가 없으면 NaN. */
    private fun earAmp(): Float = if (earPeak.isFinite() && earTrough.isFinite()) earPeak - earTrough else Float.NaN

    /** 상단 프레임(신호 ≥ 정점 − [TOP_KNEE_BAND])의 무릎각 중앙값. 재료가 없으면 NaN. */
    private fun kneeTop(): Float {
        val ks = topKnees.filter { it.first >= peak - TOP_KNEE_BAND }.map { it.second }
        return if (ks.isEmpty()) Float.NaN else median(ks)
    }

    /** 크런치 귀 탐침 — 귀 들림의 1차원 사이클이 닫혔는데 그동안 주 신호가 움직이지 않았으면 목만 당긴 회. */
    private fun probeStep(t: Long, x: Float, g: (String) -> Float?) {
        val e = g(FloorChain.EAR_LIFT) ?: return
        probeLastAt?.let { if (t - it > MAX_GAP_MS) { dropProbe(); earMin = Float.POSITIVE_INFINITY } }
        probeLastAt = t
        if (!probeMoving) {
            if (e < earMin) earMin = e
            val eb = earBase
            if (e >= earMin + PROBE_DEPART && (eb == null || earMin <= eb + PROBE_SLACK)) {
                probeMoving = true; probeStart = t; probeStartMin = earMin; probePeak = e; probePeakYaw = g(FloorChain.YAW); probePeakRatio = g(FloorChain.RATIO)
                probeMainMax = x; probeReturnAt = null; probeDwell.clear()
            }
            return
        }
        if (e > probePeak) { probePeak = e; probePeakYaw = g(FloorChain.YAW); probePeakRatio = g(FloorChain.RATIO) }
        probeMainMax = maxOf(probeMainMax, x)
        if (t - probeStart > profile.maxCycleMs) { dropProbe(); earMin = e; return }
        val thr = probePeak - RETURN_FRACTION * (probePeak - probeStartMin)
        if (e > thr) { probeReturnAt = null; probeDwell.clear(); return }
        if (probeReturnAt == null) probeReturnAt = t
        probeDwell += e
        if (t - probeReturnAt!! < DWELL_MS || probeDwell.size < DWELL_FRAMES || t - probeStart < MIN_CYCLE_MS) return
        val mainMoved = moving || lastMainDepartAt >= probeStart || lastMainEndAt >= probeStart
        val sideOk = probePeakYaw?.let { it <= SIDE_YAW_MAX_RELATIVE } == true   // 상대 사유 — 측면도만
        if (!mainMoved && sideOk && "neck_only" in enabled) {
            reject(t, "neck_only", probeStartMin, probePeak, FloorRejection(t, "neck_only", trunkPeak = probeMainMax, earPeak = probePeak, amp = probePeak - probeStartMin,
                kneeTop = Float.NaN, headMed = Float.NaN, sideOk = true))
        }
        earMin = probeDwell.min()
        dropProbe()
    }

    /** 세지 않은 동작 하나를 기록한다(틱·이유 음성의 원천). 세지 않은 동작도 세트가 시작됐다는 증거라 잠정 첫 회를 확정한다. */
    private fun reject(t: Long, reason: String, startMin: Float, peak: Float, detail: FloorRejection) {
        rejected += RepRejected(t, startMin, peak, Float.NaN, reason)
        rejectedDetail += detail
        tentativeAt = null
    }

    /**
     * 상황 안내(설계 §4.5·§4.6) — 판정 프레임마다(사람이 안 보여도) 부른다. 사이클 신호가 [SIGNAL_LOST_MS] 끊기면 [SIGNAL_LOST_CUE_GAP_MS] 간격으로,
     * 누운 기준이 세트 처음 [NO_BASE_CUE_MS] 동안 안 잡히면 한 번. 말할 것이 없으면 null.
     */
    fun guideCue(nowMs: Long): String? {
        val first = firstSeenAt ?: nowMs.also { firstSeenAt = it }
        val seen = lastSignalAt ?: first
        if (nowMs - seen >= SIGNAL_LOST_MS && nowMs - lostCuedAt >= SIGNAL_LOST_CUE_GAP_MS) { lostCuedAt = nowMs; return signalLostCue(profile, lastMissing) }
        if (lying == null && !noBaseCued && nowMs - first >= NO_BASE_CUE_MS) { noBaseCued = true; return noBaseCue(profile) }
        // 출구로 기준을 잃고(일어나 앉음) 다시 못 잡으면 한 번 더 — 전에는 세트 처음 한 번뿐이라 일어난 뒤 이어지는 동작이 통째로 무음이었다
        val lost = baseLostAt
        if (base == null && lost != null && !baseLostCued && nowMs - lost >= NO_BASE_CUE_MS) { baseLostCued = true; return noBaseCue(profile) }
        return null
    }

    // ---- 기준
    private fun lyingRegion(f: Map<String, Float>, x: Float): Boolean {
        val axis = f[FloorChain.AXIS_H]?.takeIf { it.isFinite() }
        val gate = axis == null || axis <= LYING_AXIS_MAX           // 축각이 없음 = up 미확인 → 게이트 없이
        return gate && (profile != FloorProfile.LEG_RAISE || x <= LEG_LYING_THIGH_MAX)
    }

    private fun addLying(t: Long, x: Float, torso: Float?, ear: Float?, leg: Float?, face: Float?) {
        lyingWin.addLast(LyingFrame(t, x, torso, ear, leg, face))
        while (lyingWin.isNotEmpty() && lyingWin.first().t < t - LYING_WINDOW_MS) lyingWin.removeFirst()
        if (lyingWin.size < LYING_MIN_FRAMES) return
        val b = base
        if (b == null) { establish(lyingWin.map { it.sig }, lyingWin.mapNotNull { it.torso }, lyingWin.mapNotNull { it.ear }, lyingWin.mapNotNull { it.leg }, lyingWin.mapNotNull { it.face }, fromPrep = false); return }
        val cand = median(lyingWin.map { it.sig })
        if (cand < b) base = cand
        lyingWin.mapNotNull { it.torso }.takeIf { it.size >= LYING_MIN_FRAMES }?.let { torsoBase = median(it) }
        lyingWin.mapNotNull { it.ear }.takeIf { it.size >= LYING_MIN_FRAMES }?.let { earBase = median(it) }
        lyingWin.mapNotNull { it.face }.takeIf { it.size >= LYING_MIN_FRAMES }?.let { faceBase = median(it) }
    }

    /** [fromPrep] = 준비 프레임에서 심었다(로그 [LYING_PREP] — 재생기는 그때만 같은 값을 심는다, [restoreLying]). */
    private fun establish(sigs: List<Float>, torsos: List<Float>, ears: List<Float>, legs: List<Float>, faceVals: List<Float>, fromPrep: Boolean) {
        val b = median(sigs)
        base = b; floorMin = minOf(floorMin, sigs.min()); fromRest = true; legMinIdle = Float.POSITIVE_INFINITY; baseLostAt = null
        torsoBase = torsos.takeIf { it.isNotEmpty() }?.let(::median)
        earBase = ears.takeIf { it.isNotEmpty() }?.let(::median)
        faceBase = faceVals.takeIf { it.isNotEmpty() }?.let(::median)
        if (legBase == null) legBase = legs.takeIf { it.isNotEmpty() }?.let(::median)
        if (lying == null) lying = buildMap {
            put(profile.signal, b); torsoBase?.let { put(FloorChain.TORSO_ELEV, it) }; earBase?.let { put(FloorChain.EAR_LIFT, it) }; legBase?.let { put(FloorChain.LEG, it) }
            faceBase?.let { put(FloorChain.FACE, it) }
            put(LYING_PREP, if (fromPrep) 1f else 0f)
            if (fromPrep) put(LYING_MIN, sigs.min())
        }
    }

    private fun exit(t: Long) {
        if (base != null || moving) discarded += FloorDiscard(t, "exit")
        if (moving) {
            lastMainEndAt = t
            // 판별 사유가 있는 후보(느린 상단의 윗몸일으키기 — 정점 > 80°)는 보류한다. 곧 돌아오면 그 사유로 기각하고 말한다(onFrame)
            val sideOk = sideLoose()
            val reason = if (sideOk && raisedFrames >= MIN_RAISED_FRAMES) identity(peak - startMin, strict = sideStrict()) else null
            if (reason != null) exitPending = ExitPending(t, reason, startMin, peak, peak - RETURN_FRACTION * (peak - startMin),
                trunkPeak = if (profile == FloorProfile.CRUNCH) peak else finiteOr(torsoTiltMax),
                earPeak = if (profile == FloorProfile.CRUNCH) finiteOr(earPeak) else Float.NaN,
                amp = peak - startMin, kneeTop = kneeTop(), headMed = if (heads.isEmpty()) Float.NaN else median(heads))
        }
        if (base != null) { baseLostAt = t; baseLostCued = false }
        clearCycle(); dropProbe(); lyingWin.clear()
        base = null; torsoBase = null; earBase = null; faceBase = null
        floorMin = Float.POSITIVE_INFINITY; earMin = Float.POSITIVE_INFINITY; legMinIdle = Float.POSITIVE_INFINITY; fromRest = true
    }

    private fun retractFirst() {
        val c = completed.removeAt(0)
        retracted += c.tMs
        repDetail.indexOfFirst { it.tMs == c.tMs }.takeIf { it >= 0 }?.let { repDetail.removeAt(it) }
        newlyRetracted = true
    }

    private fun clearCycle() {
        moving = false; peak = Float.NEGATIVE_INFINITY; peakYaw = null; peakRatio = null; earPeak = Float.NEGATIVE_INFINITY; torsoTiltMax = Float.NEGATIVE_INFINITY
        kneeGapPeak = null; peakDiff = null; startYaw = null; startRatio = null; raisedFrames = 0; topKnees.clear(); heads.clear(); faces.clear(); faceCams.clear(); gazes.clear(); cycleFrames.clear(); returnAt = null; dwell.clear()
    }

    private fun dropProbe() { probeMoving = false; probePeak = Float.NEGATIVE_INFINITY; probePeakYaw = null; probePeakRatio = null; probeMainMax = Float.NEGATIVE_INFINITY; probeReturnAt = null; probeDwell.clear() }

    private fun finiteOr(v: Float): Float = if (v.isFinite()) v else Float.NaN
    private fun median(xs: List<Float>): Float { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

    companion object {
        const val VERSION = "floorcycle_v1_beta"

        /**
         * 판별 기각의 이유(음성, 두 모드) — 횟수의 입장 조건이라 자세 코칭이 아니라 "무엇이 있어야 세는가" 를 말한다. null = 말하지 않는다.
         * `timeout`·`exit` 는 세트 끝 동작이라 기각 목록에 들지 않는다(틱도 없다).
         */
        fun cueFor(profile: FloorProfile, reason: String?): String? = when (profile) {
            FloorProfile.CRUNCH -> when (reason) {
                "sit_up" -> "끝까지 일어나면 크런치로 세지 않아요. 어깨만 바닥에서 들었다 내려와 주세요."
                "neck_only" -> "목만 당기면 세지 않아요. 어깨가 바닥에서 뜨도록 상체를 말아 올려 주세요."
                "arms_only" -> "팔만 움직이면 세지 않아요. 팔은 고정하고 상체를 말아 올려 주세요."
                "shallow" -> "어깨를 바닥에서 더 들어 올려야 세요."
                else -> null
            }
            FloorProfile.LEG_RAISE -> when (reason) {
                "trunk_up" -> "상체를 들면 세지 않아요. 등을 바닥에 대고 다리만 올려 주세요."
                "knee_bent" -> "무릎을 굽히면 세지 않아요. 다리를 편 채 올려 주세요."
                "shallow" -> "다리를 더 높이 올려야 세요."
                "one_leg" -> "두 다리를 함께 올려야 세요."
                "feet_touch" -> "발이 바닥에 닿으면 세지 않아요. 바닥 바로 위에서 멈췄다 올려 주세요."
                else -> null
            }
        }

        /**
         * 기각 사유의 화살표(§101, 문장 표 [cueFor] 옆) — 전부 중력 위 기준([MotionFrame.GRAVITY], 폰을 눕히면 화면 위 ≠ 중력 위)·보이는 쪽 사슬([MotionPick.CHAIN]).
         * sit_up·feet_touch·arms_only 는 '덜 하라' 는 지적이라 점 — 쉬는 자세에서 방향 화살표를 그리면 반대로 읽힌다. 10-08 크런치 센 회의 어깨 변위·중력 위 cos 중앙값 0.72.
         */
        fun motionFor(profile: FloorProfile, reason: String?): FormMotion? = when (profile) {
            FloorProfile.CRUNCH -> when (reason) {
                "shallow", "neck_only" -> FormMotion(MotionAnchor.SHOULDERS, high = MotionKind.UP, pick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
                "sit_up" -> FormMotion.mark(MotionAnchor.SHOULDERS, pick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
                "arms_only" -> FormMotion.mark(MotionAnchor.WRISTS, pick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
                else -> null
            }
            FloorProfile.LEG_RAISE -> when (reason) {
                "shallow", "one_leg" -> FormMotion(MotionAnchor.ANKLES, high = MotionKind.UP, frame = MotionFrame.GRAVITY)
                "trunk_up" -> FormMotion(MotionAnchor.SHOULDERS, high = MotionKind.DOWN, pick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
                "knee_bent" -> FormMotion.mark(MotionAnchor.KNEES, frame = MotionFrame.GRAVITY)
                "feet_touch" -> FormMotion.mark(MotionAnchor.ANKLES, frame = MotionFrame.GRAVITY)
                else -> null
            }
        }

        /** 누운 기준이 잡히지 않을 때 한 번(설계 §4.5·§4.6), 출구로 기준을 잃고 다시 못 잡을 때 한 번 더. */
        fun noBaseCue(profile: FloorProfile): String = when (profile) {
            FloorProfile.CRUNCH -> "누운 자세가 보이면 세기 시작해요. 몸 옆에서 머리부터 발목까지 보이게 해 주세요."
            FloorProfile.LEG_RAISE -> "누워서 다리를 바닥에 내리면 세기 시작해요."
        }
        /** 몸통(어깨·골반)이 빠졌을 때의 신호 결측 안내. */
        const val SIGNAL_LOST_CUE = "어깨와 골반이 보이게 해 주세요. 지금은 세지 못해요."

        /**
         * 사이클 신호가 끊겼을 때의 안내 — [missing] = 마지막으로 신호가 빠진 프레임에서 빠진 관절. 크런치 현 들림은 발목(접지선 발목→골반)이, 레그 레이즈 허벅지는 무릎이
         * 있어야 계산된다. 사람이 아예 안 잡혀 모르면(null) 그 종목에 필요한 관절 전부를 말한다.
         */
        fun signalLostCue(profile: FloorProfile, missing: String?): String = when (missing) {
            MISSING_TORSO -> SIGNAL_LOST_CUE
            MISSING_ANKLE -> "발목까지 보이게 해 주세요. 지금은 세지 못해요."
            MISSING_KNEE -> "무릎이 보이게 해 주세요. 지금은 세지 못해요."
            else -> when (profile) {
                FloorProfile.CRUNCH -> "어깨와 골반, 발목이 보이게 해 주세요. 지금은 세지 못해요."
                FloorProfile.LEG_RAISE -> "어깨와 골반, 무릎이 보이게 해 주세요. 지금은 세지 못해요."
            }
        }
        const val MISSING_TORSO = "torso"
        const val MISSING_ANKLE = "ankle"
        const val MISSING_KNEE = "knee"

        /** 로그 `reps.config.lying` 의 출처 키 — 1 = 준비 프레임에서 심음, 0 = 세트 중에 잡음([restoreLying] 은 1 일 때만 심는다). */
        const val LYING_PREP = "prep"
        /** 로그 `reps.config.lying` 의 준비 프레임 신호 최솟값(출발 최소점 — 재생기가 같은 상태를 만든다). */
        const val LYING_MIN = "min"

        const val RETURN_FRACTION = 0.6f        // MM-Fit ±1 15/15. 0.5 는 헛사이클 15 → 19
        const val DWELL_MS = 150L
        const val DWELL_FRAMES = 2
        const val MIN_CYCLE_MS = 800L
        const val MAX_GAP_MS = 750L             // LegCycleTracker 와 같다. 레거시 1.5 s 는 진행 회를 버렸다
        const val LYING_AXIS_MAX = 30f          // 잠정 — 폰 배치 블록
        const val LEG_LYING_THIGH_MAX = 25f
        const val LYING_WINDOW_MS = 1_500L
        /**
         * 누운 기준을 이루는 최소 프레임(최근 [LYING_WINDOW_MS] 안). 설계 §4.2 는 3 이었으나 2 로 둔다 — MM-Fit 윗몸일으키기(300 ms 격자, 화면 가로 = 수평 가정) 재생에서
         * 쉬지 않는 w06·w14 는 회마다 누운 영역(축 ≤ 30°) 프레임이 1~2 개뿐이라 3 이면 기준이 끝내 안 잡혀 6세트 모두 0회였다(2 면 9·8·9·9·4·9, 크런치형 w20 은 10·10·9 그대로).
         * 원칙: 쉬지 않는 사용자를 0회로 만들지 않는다(설계 §2 #3). 2026-10-07.
         */
        const val LYING_MIN_FRAMES = 2
        const val EXIT_MS = 1_500L
        /** 출구가 먼저 걸린 판별 후보를 보류하는 시간 — 그 안에 누운 쪽(정점에서 60 % 되돌아옴)으로 돌아오면 기각, 아니면 세트 끝 일어남(소리 없이). 크런치 최대 사이클과 같다. */
        const val EXIT_RETURN_MS = 5_000L
        const val CRUNCH_EXIT_DEG = 50f
        const val LEG_EXIT_TILT = 45f
        const val FIRST_REP_CONFIRM_MS = 8_000L  // RepCounter.FIRST_REP_CONFIRM_MS 와 같다 — 종목별 창은 FloorProfile.firstRepConfirmMs
        /** 첫 회 확인 창의 여유 — 최대 사이클을 다 쓴 둘째 회도 창 안에 닫히게. */
        const val FIRST_REP_SLACK_MS = 2_000L
        const val PREP_WINDOW_MS = 1_600L
        const val SIDE_YAW_MAX = 0.15f          // 절대각 사유(sit_up)의 측면 — 판별 띠는 측면(C)에서만 쟀다
        /**
         * 상대·비율 사유(shallow·one_leg·knee_bent·trunk_up·neck_only)의 측면 상한(2026-10-08). 이 폰 사용자의 자연스러운 배치가 yaw 0.16~0.23 이라 0.15 로는 판별이 한 번도
         * 돌지 않았다(10-07 크런치 12/15 유보, 10-08 레그 레이즈 12/12 유보). 두 허벅지 차·진폭 비율·무릎각은 ±17° 돌아선 투영에 둔감하다. AIHub C 정상 클립 출발 yaw p90 0.10.
         */
        const val SIDE_YAW_MAX_RELATIVE = 0.30f
        /** 한 칸짜리 튐은 회가 아니다 — 출발선 위 판정 칸이 이보다 적으면 소리 없이 버린다(`discarded` [DISCARD_SPIKE]). 실제 회는 300 ms 격자에서 3칸 이상. */
        const val MIN_RAISED_FRAMES = 2
        const val DISCARD_SPIKE = "spike"
        /** 본인 기준 진폭을 만드는 처음 센 회 수(컬 §62c 와 같다). */
        const val REF_REPS = 3
        /**
         * 측면의 두 번째 조건(2026-10-07): 어깨–발목 ÷ 투영 어깨폭(`fc_ratio`) ≥ 8 — 있을 때만 묻는다. `fc_yaw` 는 먼–가까운 어깨·골반의 몸통축 성분만 봐서
         * 발 쪽·머리 쪽에서 찍은 끝 방향 촬영(AIHub E)도 통과했다 — E 크런치에서 측면으로 판정된 42회 중 7회가 투영이 무너진 채(현 들림 107~155°, ratio 3.6~6.9) sit_up 으로 기각됐다.
         * 플랭크 준비 조건과 같은 값이고 AIHub C 통과 99.6 %.
         */
        const val SIDE_RATIO_MIN = 8f
        const val TOP_BAND_FRACTION = 0.2f      // 로그의 상단 체류 띠
        // 크런치 판별(설계 §4.5)
        const val SIT_UP_DEG = 80f              // 정상 회 0.3 %, 완전 윗몸일으키기 57~93 % 거름(Q8)
        /** 크런치 얕음의 절대 하한 — [FloorProfile.CRUNCH] `shallowDeg`(10°, 2026-10-08 켬: 10-08 폰 팔만 움직인 회 4.8~8.3°·어깨만 든 회 5~13° vs 10-07 제대로 든 회 18~31°). AIHub 8° 가 9.2 % 였으나 16프레임 분할 의존이라 입장 측정은 MM-Fit 윗몸일으키기·폰 블록 세트. */
        val CRUNCH_SHALLOW_DEG: Float get() = FloorProfile.CRUNCH.shallowDeg
        const val PROBE_DEPART = 6f             // 정상 회 귀 들림 p2 8.4°
        /** 팔만 움직임(§101): 회 창(출발 650 ms 전부터)의 손목 이동 ≥ 0.8 몸통 ∧ 귀 들림 진폭 < 10°. 손목 버퍼 보관 시간, 팔 자세 경계(팔꿈치각). */
        const val ARMS_ONLY_SWEEP = 0.8f
        const val ARMS_ONLY_EAR_DEG = 10f
        const val ARMS_WINDOW_LEAD_MS = 650L
        const val WRIST_KEEP_MS = 8_000L
        const val ARM_FOLDED_DEG = 90f
        const val ARM_EXTENDED_DEG = 120f
        const val PROBE_SLACK = 8f
        // 레그 레이즈 판별(설계 §4.6)
        const val LEG_TRUNK_UP_DEG = 30f
        /** 상단 무릎각 하한 — 2026-10-08 켬(110 → 100 으로 내려 입장 여유: AIHub C 110° 에서 1.5 %). 10-07·10-08 폰 무릎 접기 48~85°, 정상 회 131~149°. */
        const val LEG_KNEE_BENT_DEG = 100f
        const val TOP_KNEE_BAND = 15f
        /** 레그 레이즈 얕음의 절대 하한 — [FloorProfile.LEG_RAISE] `shallowDeg`(30°, 473 회 0.9 %). 본인 0.7배가 더 크면 그쪽(10-07 절반 높이 52~55° vs 본인 92°). */
        val LEG_SHALLOW_DEG: Float get() = FloorProfile.LEG_RAISE.shallowDeg
        /** 한 다리 — 정점 두 허벅지 차(°). AIHub 전 클립 양다리 정점 C 3/160 = 1.9 %·E 1.6 %(p98 22°), FMS 한 다리 90/90, 10-08 폰 한 다리 회 80~118°. */
        const val ONE_LEG_DIFF = 35f
        const val ONE_LEG_GAP = 0.45f           // 먼 허벅지가 없을 때의 대안 — 두 무릎 간격(정상 p99 0.26~0.59, AIHub C 3.1 %)
        const val FEET_TOUCH_MARGIN = 3f        // 꺼 둠(Q11) — AIHub 정상도 바닥까지 내린다
        // 안내
        const val NO_BASE_CUE_MS = 5_000L
        const val SIGNAL_LOST_MS = 3_000L
        const val SIGNAL_LOST_CUE_GAP_MS = 15_000L
    }
}
