package com.example.trex_kotlin.posture

/**
 * 바닥 세 종목의 시선 분류(spec §101d, `docs/GAZE_DESIGN_2026-10-09.md` — 사용자 결정 2026-10-09 저녁 "MediaPipe Face Landmarker 추가로 진행").
 *
 * 정의: 시선 = **머리가 몸의 긴 축을 중심으로 돌아가지 않은 상태**(요 ≈ 0). 폰이 몸 옆 바닥(측면)이면 올바른 시선(크런치 턱 당겨 무릎 앞·레그 레이즈 천장·플랭크 양손 사이 바닥)은
 * 카메라에 **옆얼굴**로 보이고, 폰 화면을 보면 얼굴이 카메라 쪽으로, 반대편을 보면 카메라 반대쪽으로 돈다. 눈동자는 보지 않는다.
 *
 * 재료(`PostureAnalyzer` 가 판정 칸마다 머리 크롭에 얼굴 메시 모델을 돌려 낸다):
 *  - [FOUND] 얼굴이 검출됐는가(1/0). 얼굴 모델이 돌지 않은 프레임(서서 하는 종목·모델 실패·머리가 안 보여 크롭이 없음)에는 키가 없다.
 *  - [YAW] 얼굴 앞 방향과 화면 평면이 이루는 각(°): **0 = 옆얼굴, + = 카메라 쪽, − = 카메라 반대쪽**(±90 = 정면·뒤통수). 얼굴 자세 행렬(facial transformation matrix)의 z 축에서.
 *
 * **측면 폰이 볼 수 있는 것은 '카메라 쪽' 뿐이다(폰 1차 확인 2026-10-10).** 카메라를 향한 얼굴은 프레임의 95 % 넘게 검출되고(요 +50~80°), 옆얼굴(정면 시선)은 2~5 % 만,
 * 뒤통수(반대쪽)는 0 — 그래서 **미검출 = 화면 밖**([OFF_SCREEN]: 얼굴이 카메라를 향하지 않음, 정면인지 반대쪽인지는 모름)이다. §101d 초판의 '옆얼굴을 본 뒤의 미검출 = 반대쪽'
 * (`profileSeen`)은 폐기했다 — 옆얼굴은 어쩌다 한 프레임만 검출되고 그 뒤의 미검출은 거의 다 그대로 옆얼굴이라, 10-10 레그 레이즈에서 천장을 보던 4회를 반대쪽으로 읽고
 * "고개가 반대쪽으로 돌아갔어요" 를 틀리게 말했다(원칙 #1·#6). Pose 의 얼굴 점(귀 간격·코 위치·세계 z)은 옆얼굴과 뒤통수에서 같다(가려진 점을 지어낸다, 설계 §2) —
 * **반대쪽은 측면 배치에서 관측 불가**. [AWAY] 는 요 < −25° 로 **검출된** 얼굴에만 남긴다(측면에서는 사실상 나오지 않는다).
 */
object FloorGaze {
    const val FOUND = "face_found"
    const val YAW = "face_yaw"
    /** 얼굴 모델 추론 시간(ms) — 진단용 피처. */
    const val INFER_MS = "face_infer_ms"
    /** 얼굴 검출에 쓴 머리 크롭의 회전(연속 °) — 목 방향(어깨 중점 → 귀 중점)이 위로 오게 돌린 값. 진단용. */
    const val ROTATION = "face_rot"
    /** 머리 크기 추정(px, 분석 영상 기준 — 코–귀 × 2.2 와 목 길이의 최댓값). 진단용. */
    const val SIZE = "face_px"

    const val UNKNOWN = 0
    const val FRONT = 1
    const val CAMERA = 2
    const val AWAY = 3
    /** 얼굴 모델이 머리 크롭에서 얼굴을 못 찾음 = 카메라를 향하지 않음(옆얼굴·뒤통수 중 무엇인지는 모름). 틀린 시선이 아니고, '화면 쪽' 지적의 통과다. */
    const val OFF_SCREEN = 4

    /** 틀린 시선의 요 띠(°) — 사용자 세트의 '카메라 쪽' 이 +50~80°(10-10), 옆얼굴 검출 프레임 0~10°(설계 §3, 잠정 G2). */
    const val TURN_DEG = 25f

    /** 프레임 하나의 상태. [chainVisible] = 몸 사슬이 보이는가(`fc_chain = 1`) — 사람이 없으면 얼굴 미검출은 모름이다. */
    fun classify(found: Float?, yaw: Float?, chainVisible: Boolean): Int {
        if (found == null || !found.isFinite()) return UNKNOWN
        if (found < 0.5f) return if (chainVisible) OFF_SCREEN else UNKNOWN
        if (yaw == null || !yaw.isFinite()) return UNKNOWN
        return when {
            yaw > TURN_DEG -> CAMERA
            yaw < -TURN_DEG -> AWAY
            else -> FRONT
        }
    }

    fun classify(f: Map<String, Float>): Int = classify(f[FOUND], f[YAW], f[FloorChain.CHAIN] == 1f)

    /** 틀린 시선인가(카메라 쪽·반대쪽). 모름·정면·화면 밖은 아니다. */
    fun wrong(state: Int): Boolean = state == CAMERA || state == AWAY

    /**
     * [state] 가 '시선 벗어남' 지적의 교정인가(§100 — 말한 지적의 검사가 통과 판정을 받은 때만). 사용자 결정 2026-10-10 오후 "반대편·휴대폰을 구분하지 말고 벗어났다는 것만 알려라" —
     * 벗어남 = 얼굴이 카메라를 향함(측면 폰이 볼 수 있는 유일한 틀림)이라, 정면이든 화면 밖(카메라를 향하지 않음)이든 벗어남이 아니면 교정이다. 모름은 아니다.
     */
    fun recovers(state: Int): Boolean = state == FRONT || state == OFF_SCREEN

    /** 화면 꼬리표 — 사용자 결정(10-10 오후)으로 화면 쪽·반대쪽을 가르지 않고 '벗어남', 카메라를 향하지 않는 미검출은 '범위 안'. */
    fun label(state: Int): String = when (state) { FRONT -> "정면"; CAMERA, AWAY -> "벗어남"; OFF_SCREEN -> "범위 안"; else -> "측정 중" }

    /** 로그 키(세트 로그 `gaze`). */
    fun key(state: Int): String = when (state) { FRONT -> "front"; CAMERA -> "camera"; AWAY -> "away"; OFF_SCREEN -> "off_screen"; else -> "unknown" }

    /** 프레임 목록의 다수결 — 모름을 뺀 프레임이 절반 미만이면 모름. 화면 밖은 아는 상태라 옆얼굴 구간의 어쩌다 한 프레임 검출(10-10 크런치 요 82°)을 흡수한다. */
    fun majority(states: List<Int>): Int {
        if (states.isEmpty()) return UNKNOWN
        val known = states.filter { it != UNKNOWN }
        if (known.size * 2 < states.size) return UNKNOWN
        return known.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: UNKNOWN
    }
}
