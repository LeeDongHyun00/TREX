package com.example.trex_kotlin.posture

/**
 * 교정 화살표 어휘(docs/LIVE_SCREEN_REDESIGN.md §3, spec §101 화살표) — 말한 교정 문장 하나가 **어느 관절에서 어느 쪽으로** 움직이라고 그릴지.
 * 새 판정이 아니다: 음성 문장을 만든 그 사건이 화살표도 만든다(COACH 만 — 음성과 같은 문턱). 정책은 "COACH 에서 말한 교정 문장 하나의 그림"(사용자 결정 2026-10-08 U7):
 * 반복 검사(ship)·판별 기각 사유·플랭크 멈춤·바닥 시선·창 규칙·가동 범위가 모두 같은 어휘를 쓴다. 말하지 않는 beta 와 유보는 그리지 않는다.
 * 방향을 화면 평면에 그릴 수 없는 지적(앞뒤 깊이·관절 굽힘·폄·흔들림)은 화살표 대신 **점**([MotionKind.MARK])이다 — 방향을 지어내지 않는다(원칙 #1·#5).
 * 방향 벡터는 화면에서 계산하므로(정중선 = 엉덩이 중점) 미러·촬영 뷰에 자동으로 맞는다. 재생기 소스 목록에 들어가므로 안드로이드에 기대지 않는다.
 */
enum class MotionAnchor(val landmarks: Set<Int>) {
    SHOULDERS(setOf(11, 12)), ELBOWS(setOf(13, 14)), WRISTS(setOf(15, 16)), HIPS(setOf(23, 24)),
    KNEES(setOf(25, 26)), ANKLES(setOf(27, 28)), FEET(setOf(31, 32)),
    /** 코 하나 — 시선(고개 방향) 검사(spec §100). 귀(7·8)는 무대가 점으로 그리지 않아 화살표가 보이지 않는 점에서 나갔다(10-08 보고) — 코만 둔다. */
    HEAD(setOf(0));
}

enum class MotionKind {
    /** 정중선(엉덩이 중점 x) 쪽으로 — 발·무릎 모으기. */
    TOWARD_MIDLINE,
    /** 정중선에서 바깥으로 — 무릎을 발끝 방향으로. */
    AWAY_MIDLINE,
    UP, DOWN,
    /** 엉덩이 중점 쪽으로 — 팔꿈치를 옆구리에. [TOWARD_TARGET] 의 목표 = 골반 특례. */
    TOWARD_TORSO,
    /** 관절을 축으로 안쪽 회전 — 발끝을 안으로. */
    ROTATE_IN,
    /** 바깥 회전 — 발끝을 바깥으로. */
    ROTATE_OUT,
    /** 앵커에서 [FormMotion.target] 관절(들)의 중심 쪽으로 — 팔꿈치→무릎, 골반→굽힌 발목. */
    TOWARD_TARGET,
    /** 위치만(붉은 고리) — 방향을 화면 평면에 그릴 수 없는 지적(앞뒤 깊이·굽힘·폄·흔들림). 방향을 지어내지 않는다(원칙 #5). */
    MARK;

    /** 좌우 방향 — 옆(SIDE_B/SIDE_D)에서는 화면 x 가 깊이축이라 그릴 수 없어 [MARK] 로 낮춘다. */
    val lateral: Boolean get() = this == TOWARD_MIDLINE || this == AWAY_MIDLINE || this == ROTATE_IN || this == ROTATE_OUT
}

/** 앵커 관절 중 어느 쪽에 그리는가 — [ALL] 양쪽, [MOVING] 그 반복의 움직인 다리(쪽), [SUPPORT] 반대 다리(런지 뒷무릎), [NEAR] 카메라 쪽 팔(컬), [CHAIN] 바닥 계열의 보이는 쪽 사슬. */
enum class MotionPick { ALL, MOVING, SUPPORT, NEAR, CHAIN }

/** 위·아래의 기준 — [SCREEN] 화면 위, [GRAVITY] 중력 위(바닥 종목: 폰을 눕히면 화면 위 ≠ 중력 위, 10-08 레그 레이즈 원시 up 72/215 프레임 뒤집힘). */
enum class MotionFrame { SCREEN, GRAVITY }

/**
 * 위반 방향별 화살표. 한쪽 띠만 있는 검사는 그 방향만 둔다. 창 규칙([WindowMotions])은 [high] = 기본 방향, [low] = 반대측(opposite_guard).
 * @property pick 앵커 중 어느 쪽([MotionPick]) — 쪽·뷰·사슬은 그리는 쪽이 넘긴다([resolve]).
 * @property target [MotionKind.TOWARD_TARGET] 의 목표 관절과 그 쪽.
 * @property frame 위·아래의 기준([MotionFrame]).
 */
data class FormMotion(
    val anchor: MotionAnchor, val high: MotionKind? = null, val low: MotionKind? = null,
    val pick: MotionPick = MotionPick.ALL,
    val target: MotionAnchor? = null, val targetPick: MotionPick = MotionPick.ALL,
    val frame: MotionFrame = MotionFrame.SCREEN,
) {
    fun kindFor(direction: FormDirection): MotionKind? = if (direction == FormDirection.HIGH) high else low

    /** 창 규칙 사건의 방향([Direction.OPPOSITE] = 반대측 가드). null 은 기본 방향. */
    fun kindFor(direction: Direction?): MotionKind? = if (direction == Direction.OPPOSITE) low else high

    companion object {
        /** 위치만 그리는 점(두 방향 모두 [MotionKind.MARK]). */
        fun mark(anchor: MotionAnchor, pick: MotionPick = MotionPick.ALL, frame: MotionFrame = MotionFrame.SCREEN) =
            FormMotion(anchor, high = MotionKind.MARK, low = MotionKind.MARK, pick = pick, frame = frame)

        /** 카메라 쪽 팔(§62c 후속 7): B 는 사용자 오른쪽 앞(오른어깨가 카메라에 가까움) → 오른팔, D → 왼팔, 모르면 null(양쪽). */
        fun nearSide(view: String?): StepSide? = when (view) { "B", "SIDE_B", "A" -> StepSide.RIGHT; "D", "SIDE_D", "E" -> StepSide.LEFT; else -> null }

        /**
         * 앵커 관절 집합 — [pick] 을 그 반복의 [moving] 다리·[near] 팔·바닥 [chainSide](0 = MediaPipe 왼쪽, 1 = 오른쪽)로 푼다.
         * 쪽을 모르면(null) 양쪽 — 틀린 쪽에 붙이는 것보다 양쪽이 낫다(원칙 #6). 코(0)는 쪽이 없다.
         */
        fun resolve(anchor: MotionAnchor, pick: MotionPick, moving: StepSide? = null, near: StepSide? = null, chainSide: Int? = null): Set<Int> {
            val side: StepSide? = when (pick) {
                MotionPick.ALL -> null
                MotionPick.MOVING -> moving
                MotionPick.SUPPORT -> moving?.other
                MotionPick.NEAR -> near
                MotionPick.CHAIN -> chainSide?.let { if (it == 0) StepSide.LEFT else StepSide.RIGHT }
            } ?: return anchor.landmarks
            return anchor.landmarks.filter { it == 0 || (it % 2 == 1) == (side == StepSide.LEFT) }.toSet()
        }
    }
}

/** 화면 단서를 만든 음성 원천(§101) — 반복 검사·판별 기각·플랭크 멈춤·시선·창 규칙·가동 범위·처음부터 알림. 지우는 규칙이 원천마다 다르다(통과한 회는 REPFORM 만 지운다). */
enum class MotionOrigin { REPFORM, REASON, HOLD, GAZE, WINDOW, ROM, NOTICE }

/**
 * 말한 문장 하나의 그림 명세(§101) — 엔진이 만들고 화면(`SkeletonStage.MotionCue`)이 관절 집합·방향으로 푼다. 안드로이드에 기대지 않는다(재생기 소스).
 * @property side 그 반복의 움직인 다리(쪽) — [MotionPick.MOVING]·[MotionPick.SUPPORT] 의 재료. @property near 카메라 쪽 팔(컬). @property chainSide 바닥 사슬 쪽(0 왼·1 오른, null = 화면이 가시성으로 고른다).
 * @property lateralOk 좌우 방향을 그릴 수 있는 뷰인가 — 옆(SIDE_B/SIDE_D)이면 false 라 좌우 종류는 점으로 낮춘다. @property ttlMs 통과 없이 지우는 시간.
 */
data class MotionSpec(
    val motion: FormMotion, val origin: MotionOrigin, val direction: FormDirection = FormDirection.HIGH,
    val side: StepSide? = null, val near: StepSide? = null, val chainSide: Int? = null, val lateralOk: Boolean = true,
    val ttlMs: Long = DEFAULT_TTL_MS,
) {
    /** 그릴 종류 — 옆에서 본 좌우 종류는 점. 그 방향의 어휘가 없으면 null(문장만). */
    val kind: MotionKind? get() = motion.kindFor(direction)?.let { if (it.lateral && !lateralOk) MotionKind.MARK else it }
    /** 앵커 관절(쪽을 푼 뒤). CHAIN 은 화면이 가시성으로 고르므로 양쪽을 돌려준다(chainSide 가 없을 때). */
    val anchors: Set<Int> get() = FormMotion.resolve(motion.anchor, motion.pick, side, near, chainSide)
    /** [MotionKind.TOWARD_TARGET] 의 목표 관절(쪽을 푼 뒤) — 없으면 빈 집합. */
    val targets: Set<Int> get() = motion.target?.let { FormMotion.resolve(it, motion.targetPick, side, near, chainSide) } ?: emptySet()

    companion object {
        const val DEFAULT_TTL_MS = 12_000L
        /** 플랭크 멈춤의 화살표 수명 — 재개까지이되 상한 60 s(10-08 골반 멈춤 24 s). */
        const val HOLD_TTL_MS = 60_000L
        /** 뷰 글자로 좌우 가능 여부 — 옆(SIDE_*)만 불가. 모르면(null) 가능으로 본다(정면 권장 종목). */
        fun lateralOk(view: String?): Boolean = view == null || !view.startsWith("SIDE")
    }
}

/**
 * 세트 창 규칙(`CoachCues`, `rules_mp_v0.json` ship)의 화살표 — **규칙 id 별 명시 표**(spec §101). 종전의 기준 피처 접두사 화이트리스트(torso_incl·spine…)는
 * 규칙의 op·방향을 보지 않아 딥스 '상체 살짝 숙임 유지'(torso_incl__mean < 20.8 = 너무 섬, 음성 "앞으로 숙이세요")에 어깨 **위** 화살표를 그렸다(음성과 반대, 10-08 보고 RC3).
 * 카탈로그 서서 종목의 ship 창 규칙(반복 검사가 대체한 것 제외) 24개를 셋으로 나눈다 — 화살표 7 · 보류(점) 5 · 점 12:
 *  - 화살표: 위아래(중력)·좌우를 화면에 그릴 수 있는 규칙만.
 *  - 보류(점, 사용자 결정 D8): 문장과 측정이 어긋난 규칙 — OHP '척추의 중립'(head_pitch 를 재면서 '가슴/몸통'), OHP '전완 수직'(grip_w 를 재면서 '팔꿈치'),
 *    사이드 크런치 '시선 정면 유지'(방향 없는 문장), 행잉 '어깨와 귀'(grip_w). 문장 정리 뒤 화살표를 붙인다.
 *  - 점: 앞뒤 깊이·굽힘·폄·흔들림(std) — 방향이 화면에 없다.
 * 표에 없는 규칙은 화살표가 없다(테스트 `CueMotionCoverageTest` 가 ship 창 규칙의 누락을 잡는다).
 */
object WindowMotions {
    private val SH_UP = FormMotion(MotionAnchor.SHOULDERS, high = MotionKind.UP)

    val table: Map<String, FormMotion> = mapOf(
        // ---- 화살표 7
        "바벨 스쿼트|척추의 중립[all]" to SH_UP,
        "바벨 스쿼트|척추의 중립[flexion]" to SH_UP,
        // 기본 = 무릎이 안쪽(knee_out < 임계) → 바깥으로, 반대측 가드 = 과도하게 바깥 → 안으로
        "바벨 데드리프트|발과 무릎의 방향 일치" to FormMotion(MotionAnchor.KNEES, high = MotionKind.AWAY_MIDLINE, low = MotionKind.TOWARD_MIDLINE),
        // 기본 = 고개 젖힘(face_vs_torso 큼) → 턱을 당김(아래), 반대측 = 과도 숙임 → 위
        "딥스|수축 시 고개 안 젖힘" to FormMotion(MotionAnchor.HEAD, high = MotionKind.DOWN, low = MotionKind.UP),
        // shoulder_R__p90 < 43.6 = 덜 내려감 → "90도까지 굽히세요" = 더 내려간다
        "딥스|이완 시 팔꿈치 각도 90도" to FormMotion(MotionAnchor.SHOULDERS, high = MotionKind.DOWN),
        // torso_pitch__min < −7 = 뒤로 젖힘 → 세운다
        "프런트 레이즈|상체 뒤로 숙이지 않기" to SH_UP,
        // torso_incl__mean > 9 = 과한 젖힘 → 세운다
        "랫풀 다운|수축 시 적당한 상체 젖힘" to SH_UP,
        // ---- 보류(점) 5 — 문장과 측정이 어긋남(D8)
        "오버 헤드 프레스|척추의 중립[all]" to FormMotion.mark(MotionAnchor.SHOULDERS),
        "오버 헤드 프레스|척추의 중립[flexion]" to FormMotion.mark(MotionAnchor.SHOULDERS),
        "오버 헤드 프레스|전완 지면과 수직" to FormMotion.mark(MotionAnchor.ELBOWS),
        "스탠딩 사이드 크런치|시선 정면 유지" to FormMotion.mark(MotionAnchor.HEAD),
        "행잉 레그 레이즈|어깨와 귀 사이 적당한 거리 유지" to FormMotion.mark(MotionAnchor.SHOULDERS),
        // ---- 점 12 — 앞뒤·굽힘·흔들림
        "바벨 런지|뒤다리 무릎 각도 90도" to FormMotion.mark(MotionAnchor.KNEES),
        "바벨 데드리프트|바벨 궤적과 몸 밀착" to FormMotion.mark(MotionAnchor.WRISTS),
        "굿모닝|척추의 중립[all]" to FormMotion.mark(MotionAnchor.SHOULDERS),
        "굿모닝|척추의 중립[lateral]" to FormMotion.mark(MotionAnchor.SHOULDERS),
        "굿모닝|무릎 구부린채 고정" to FormMotion.mark(MotionAnchor.KNEES),
        // 종전 접두사 표의 '어깨 위' 는 문장("앞으로 숙이세요")과 반대였다 — 앞뒤는 사선에서 그릴 수 없으므로 점
        "딥스|상체 살짝 숙임 유지" to FormMotion.mark(MotionAnchor.SHOULDERS),
        "오버 헤드 프레스|무릎 반동 없음" to FormMotion.mark(MotionAnchor.KNEES),
        "사이드 레터럴 레이즈|무릎 반동 없음" to FormMotion.mark(MotionAnchor.KNEES),
        "프런트 레이즈|팔꿈치 살짝 구부린채 고정" to FormMotion.mark(MotionAnchor.ELBOWS),
        "랫풀 다운|수축 시 몸통-팔꿈치 사이 모아줌" to FormMotion.mark(MotionAnchor.ELBOWS),
        "업라이트로우|팔꿈치가 손목 리드" to FormMotion.mark(MotionAnchor.ELBOWS),
        "행잉 레그 레이즈|두 다리 사이 모아줌 유지" to FormMotion.mark(MotionAnchor.ANKLES),
    )

    /** 규칙 id(`exercise|condition[subtype]`)와 위반 방향의 화살표. 표에 없거나 그 방향이 없으면 null(문장만). */
    fun forRule(ruleId: String, direction: Direction? = Direction.PRIMARY): FormMotion? {
        val m = table[ruleId] ?: return null
        return if (m.kindFor(direction) == null) null else m
    }

    /** 사건의 화살표 종류 — 반대측(OPPOSITE)이면 [FormMotion.low]. */
    fun kindFor(ruleId: String, direction: Direction?): MotionKind? = table[ruleId]?.kindFor(direction)
}
