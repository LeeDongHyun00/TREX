package com.example.trex_kotlin.posture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 재보정 데이터 수집용 세트 로그 (spec §9 / §14).
 *
 * 한 세트(기록 구간)의 프레임 피처·가시성·추론 통계·규칙 판정을 JSON 한 줄(JSON Lines)로 남긴다.
 * 오프라인 도구 `research/aihub_fitness/calibrate_from_logs.py` 가 이 로그와 코치 라벨(set_id 기준 CSV)을 읽어
 * 규칙 임계값을 재적합한다. 따라서 **프레임 피처는 원본 그대로**(집계 전) 기록한다 — 집계 창 정의를 바꿔도 재계산 가능.
 *
 * 스키마 `trex.posture.setlog/1`:
 * ```
 * {"schema":"trex.posture.setlog/1","set_id":"...","created_at":"2026-08-22T01:02:03Z","subject_id":null,
 *  "exercise":"바벨 스쿼트","rules_version":"mp_v0","model":"full","delegate":"GPU","front_camera":true,
 *  "up_from_gravity":true,"tilt_deg":3.2,"sample_interval_ms":300,"note":null,
 *  "frames":[{"t_ms":0,"infer_ms":63,"visible":22,"vis":[...33],"features":{"knee_L":112.3,...}}, ...],
 *  "results":[{"rule_id":"바벨 스쿼트|발과 무릎의 방향 일치","verdict":"VIOLATION","value":0.004,"n":18,
 *              "baseline_applied":false,"value_rel":null}]}   // value 는 항상 절대값, verdict 는 재배치 반영
 * ```
 * spec §58 단계 0 추가 필드(전부 선택 — 부재 = 이전 로그): `app_version`, `thermal{start,changes[{t_ms,status}]}`,
 * `reps.engine`, `reps.config{feature,min_amp,refractory_ms,max_gap_ms,complete_on_return,polarity?,return_fraction?,
 * first_pair_window_ms?,later_window_ms?,min_ratio?,max_ratio?,rom_direction?,rom_threshold?,rom_tier}`(값은 카운터가 실제로 쓴 구성),
 * `reps.resets[{t_ms,after_t_ms,reason}]`, `reps.pending{unconfirmed,in_progress,dropped[]}`(새 코어만).
 * 표시 단위(사용자 결정 2026-09-24, `RepUnit`) 추가 필드(선택 — 부재 = 이전 로그): `reps.unit`("cycle"|"side_pair"), `reps.cycles_per_rep`,
 * `reps.completed`(화면에 보인 횟수), `reps.half_pending`(세트 끝에 짝을 못 채운 한쪽이 남았을 때만 true — 아니면 키 없음).
 * `reps.count`·`t_ms`·`min`/`max`/`valid`·`invalid` 는 여전히 **카운터 사이클** 단위다(재생 파리티가 사이클에 기댄다).
 * 반복 판별 게이트(spec §62) 추가 필드 — 판별 신호가 있는 종목만(없으면 키 부재): `reps.config.identity{feature,min_amp}`,
 * `reps.rejected[{t_ms,min,max,swing}]`(세지 않은 사이클), `reps.identity_swing[]`(센 사이클의 판별 스윙, `t_ms` 와 같은 순서, 미판정은 null).
 * org.json 은 Android 유닛 테스트에서 스텁이라 직접 직렬화한다 (PostureCoreParityTest 와 같은 이유).
 */

data class SetLogFrame(
    val tMs: Long,
    val inferMs: Long,
    val visibleJointCount: Int,
    /** 33개 랜드마크 가시성(min(visibility, presence)). null 이면 기록 생략. */
    val visibility: FloatArray?,
    val features: Map<String, Float>,
    /** 검증 모드(spec §61)에서만: 정규화 이미지 좌표 33×2. null 이면 키 생략. */
    val xy: FloatArray? = null,
    /** 검증 모드에서만: MediaPipe 월드 랜드마크 원값 33×3 (m, MediaPipe 부호). */
    val world: FloatArray? = null,
    /** 검증 모드에서만: 이 프레임 피처에 쓴 up 벡터 (x, y, z). */
    val up: FloatArray? = null,
)

data class SetLogResult(
    val ruleId: String,
    val verdict: String,
    /** **항상 절대값**(기준선 차감 전). 재보정 도구가 임계값과 직접 대조하는 값이라 좌표계를 고정한다. */
    val value: Float?,
    val sampleCount: Int,
    /** 판정에 기준선 재배치가 적용됐는가 (verdict 는 상대 판정). 스키마 호환 추가 필드. */
    val baselineApplied: Boolean = false,
    /** 재배치 적용 시의 상대값(value − 기준선중앙값). 미적용이면 null. */
    val valueRel: Float? = null,
)

/**
 * 세트 로그의 렙 카운터 구성 (spec §58 단계 0) — 같은 프레임을 오프라인에서 **같은 카운터로** 다시 돌릴 수 있게 남긴다.
 * 폰 검증(Gate A)에서 "그 세트를 어떤 카운터가 셌는가" 를 로그만으로 가를 수 있어야 새 코어를 켜고 끈 전후를 비교할 수 있다.
 *
 * @property engine [ENGINE_RETURN](복귀형 `ReturnRepTracker` — 지금 앱) | [ENGINE_HYSTERESIS](`RepHysteresis` + 시작 확정, 폰 검증 전 비활성).
 * @property polarity "down"|"up". null = 레거시 경로(방향 개념 없음).
 * @property returnFraction·firstPairWindowMs·laterWindowMs·minRatio·maxRatio 새 코어 전용(레거시는 null — 키도 없다).
 *   laterWindowMs 는 새 코어에서도 null 일 수 있다(= 이후 반복은 진폭 비만 본다, 설계 §4.3) — 그때는 JSON null 로 적는다.
 * @property romTier [RepRomTier.key] — 화면·음성이 ROM 을 어떤 확신으로 말했는가.
 */
data class RepEngineLog(
    val engine: String,
    val feature: String,
    val minAmp: Float,
    val refractoryMs: Long,
    val maxGapMs: Long,
    val completeOnReturn: Boolean,
    val polarity: String? = null,
    val returnFraction: Float? = null,
    val firstPairWindowMs: Long? = null,
    val laterWindowMs: Long? = null,
    val minRatio: Float? = null,
    val maxRatio: Float? = null,
    val romDirection: String? = null,
    val romThreshold: Float? = null,
    val romTier: String? = null,
    /** 반복 판별 게이트(spec §62) — `config.identity{feature,min_amp}`. 판별 신호가 없는 종목은 null 이고 키가 없다. */
    val identityFeature: String? = null,
    val identityMinAmp: Float? = null,
) {
    companion object {
        const val ENGINE_RETURN = "return_v1"
        const val ENGINE_HYSTERESIS = "hysteresis_v1"

        /**
         * 카운터가 **실제로 쓰는** 구성을 읽어 적는다(`RepCounter.effective*`·코어·시작 확정의 값) — 복사한 상수가 아니라서
         * `forSession` 의 구성이나 코어 기본값이 바뀌어도 로그가 거짓이 되지 않는다. Gate A 재생 파리티가 이 블록에 기댄다.
         */
        fun of(counter: RepCounter): RepEngineLog {
            val s = counter.signal
            val confirm = counter.confirmationConfig
            return RepEngineLog(
                engine = if (counter.usesHysteresis) ENGINE_HYSTERESIS else ENGINE_RETURN,
                feature = s.feature,
                minAmp = s.minAmp,
                refractoryMs = counter.effectiveRefractoryMs,
                maxGapMs = counter.effectiveMaxGapMs,
                completeOnReturn = counter.effectiveCompleteOnReturn,
                polarity = s.polarity?.name?.lowercase(),
                returnFraction = counter.returnFraction,
                firstPairWindowMs = confirm?.firstPairWindowMs,
                laterWindowMs = confirm?.laterWindowMs,
                minRatio = confirm?.minRatio,
                maxRatio = confirm?.maxRatio,
                romDirection = s.romDirection,
                romThreshold = s.romThreshold,
                romTier = RepRomTier.of(s).key,
                identityFeature = s.identityFeature,
                identityMinAmp = s.identityFeature?.let { s.identityMinAmp },
            )
        }
    }
}

/**
 * 세트 중 카운터의 진행 사이클을 버린 사건 (spec §58). 시각은 전부 세트 상대 ms(프레임 t_ms 와 같은 기준). reason: "pause" | "camera_switch".
 * @property tMs 사용자가 누른(일시정지가 걸린) 벽시계 시각.
 * @property afterTMs 리셋 **직전에 카운터가 처리한 마지막 프레임**의 t_ms. null = 이 세트에서 카운터가 아직 프레임을 본 적 없다.
 *   재생은 t_ms > afterTMs 인 첫 프레임 앞에서 리셋해야 앱과 순서가 같다 — 프레임의 t_ms 는 추론 **전** 시각이라,
 *   추론 중(~60 ms)에 누른 전환은 t_ms 가 누른 시각보다 이른 프레임보다도 먼저 적용된다([tMs] 로 자르면 한 프레임 어긋난다).
 */
data class RepResetEvent(val tMs: Long, val reason: String, val afterTMs: Long? = null)

/** 세트 중 열 상태 변화 (`PowerManager.THERMAL_STATUS_*`) — [tMs] 는 세트 상대 ms. 열 상태는 추론 간격을 바꾼다(InferencePolicy). */
data class ThermalEvent(val tMs: Long, val status: Int)

data class SetLog(
    val setId: String,
    val createdAtIso: String,
    /** 수행자 식별자(선택). 재보정 시 GroupKFold 그룹으로 쓰이므로 같은 사람이면 같은 값을 넣는다. */
    val subjectId: String?,
    val exercise: String,
    val rulesVersion: String,
    val model: String,
    val delegate: String,
    val frontCamera: Boolean,
    val upFromGravity: Boolean,
    val tiltDeg: Float?,
    val sampleIntervalMs: Long,
    val frames: List<SetLogFrame>,
    val results: List<SetLogResult>,
    val note: String? = null,
    /** up 자가검증으로 뒤집어 보정한 프레임 수 / 방향을 검증한 프레임 수 (진단용, 스키마 호환 추가 필드). */
    val upFlippedFrames: Int = 0,
    val upVerifiedFrames: Int = 0,
    /** 자동 렙 카운트 (spec §27, 스키마 호환 추가 필드). null = 카운터 미적용 종목.
     *  repCount = 완료(발표) 사이클 전체, repInvalid = 그중 ROM 기준 미달로 판정된 렙. count − invalid 는 "미달로 판정되지 않은 렙" 이고
     *  ROM 을 판정하지 않은 렙(기준 없음)도 포함한다 — '유효' 가 아니다(spec §58, reps.config.rom_tier 가 판정 단계). */
    val repCount: Int? = null,
    val repTimesMs: List<Long>? = null,
    val repSignal: String? = null,
    val repInvalid: Int? = null,
    /** 렙별 사이클 극값·ROM 판정 (spec §29). 후반 드리프트(피로)·깊이 일관성을 오프라인에서
     *  렙 단위로 분석할 수 있게 한다 — repTimesMs 와 같은 순서. */
    val repMins: List<Float>? = null,
    val repMaxs: List<Float>? = null,
    val repValid: List<Boolean?>? = null,
    /** 세션 모드 (spec §29): "coach"(기본) | "track". null = 모드 개념 이전 로그. */
    val mode: String? = null,
    /**
     * 초반 창·세트 집계를 시작한 시각(세트 상대 ms, spec §31). null = 앵커 개념 이전 로그.
     * `results` 는 이 시점 이후 프레임만 집계한 값이다 — 오프라인 재계산이 같은 창을 쓰려면 이 값이 필요하다.
     * 준비 동작(폰 놓고 걸어오기·바 세팅)이 range/min/max 통계를 통째로 뒤집기 때문에 창을 자른다.
     */
    val anchorTMs: Long? = null,
    val assessmentEndTMs: Long? = null,
    val measurements: List<String> = emptyList(),
    /**
     * 세트 전체 프레임의 촬영 방향 추정 (spec §33, `ViewEstimator`): 원형 평균 요(도)·결과 벡터 길이 R·등급 문자·프레임 수.
     * null = 프레임에 view_cos/view_sin 이 없거나(§33 이전 로그·바닥 종목) 프레임 부족. 규칙 게이팅은 앵커 이후 창으로 따로 추정한다.
     */
    val viewYawDeg: Float? = null,
    val viewR: Float? = null,
    val viewClass: String? = null,
    val viewFrames: Int? = null,
    /**
     * spec §58 단계 0 — 폰 검증 로그의 재현 정보. 전부 스키마 호환 추가 필드이고 null 이면 키 자체를 쓰지 않는다(부재 = 이전 로그).
     * [repEngine]·[repResets] 는 `reps` 객체 안, [thermalStart]/[thermalChanges] 는 `thermal`, [appVersion] 은 `app_version`.
     */
    val repEngine: RepEngineLog? = null,
    /** 세트 중 카운터 리셋 사건. 카운터가 돈 §58 이후 세트는 사건이 없어도 빈 목록으로 남긴다. */
    val repResets: List<RepResetEvent>? = null,
    /** 세트 종료 시 **세지 않은** 후보(새 코어만 노출). null = 레거시 경로 또는 이전 로그 — 레거시는 이 정보를 갖지 않는다. */
    val repPending: RepPendingState? = null,
    /** 시작 확정을 못 받아 버려진 사이클(새 코어만). [repPending] 과 함께 `reps.pending` 에 들어간다. */
    val repDropped: List<RepCycle>? = null,
    /**
     * 반복 판별 게이트(spec §62)가 세지 않은 사이클 — `reps.rejected[{t_ms,min,max,swing}]`. 판별 신호가 있는 종목만 목록(비어 있어도)이고
     * 없는 종목·이전 로그는 null(키 없음). 시각은 세트 상대 ms.
     */
    val repRejected: List<RepRejected>? = null,
    /** 센 사이클마다의 판별 신호 스윙(`reps.identity_swing`, [repTimesMs] 와 같은 순서, 판정 안 한 사이클은 null). 판별 신호가 있는 종목만. */
    val repIdentitySwings: List<Float?>? = null,
    /** 반복별 자세 검사(spec §62a) — `rep_form` 블록. 평가기가 있는 종목(지금 바벨 스쿼트)만, 없으면 키 부재. */
    val repForm: RepFormLog? = null,
    /** 세트 첫 프레임 시점의 열 상태. null = 이전 로그 또는 열 상태 API 없음(API 29 미만). */
    val thermalStart: Int? = null,
    val thermalChanges: List<ThermalEvent>? = null,
    /** 앱 versionName. */
    val appVersion: String? = null,
    /**
     * 표시 횟수 단위 (사용자 결정 2026-09-24) — 카운터 사이클 몇 개를 화면의 1회로 셌는가. null = 이 필드 이전 로그(그때는 늘 사이클 = 1회).
     * [repCount]·렙별 배열은 이 단위와 무관하게 **사이클**이다. `reps.unit`·`reps.cycles_per_rep` 로 적는다.
     */
    val repUnit: RepUnit? = null,
    /** 화면에 보인(진행·자동 넘김에 쓴) 완료 횟수 — [repUnit] 단위. `reps.completed`. */
    val repCompleted: Int? = null,
    /** 세트 끝에 반대쪽을 못 채운 한쪽이 남았다(세지 않았다). true 일 때만 `reps.half_pending` 을 적는다. */
    val repHalfPending: Boolean = false,
    /**
     * 검증 모드 세트(spec §61 — 폰 검증 Gate A). true 면 `"validation":true` 와 분석 이미지 크기 `image`, 프레임마다 `xy`·`w`·`up` 을 쓴다.
     * false(기본) 면 키 자체가 없다 — 제품 로그는 바이트 그대로.
     */
    val validation: Boolean = false,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
) {
    companion object {
        const val SCHEMA = "trex.posture.setlog/1"

        fun newSetId(now: Date = Date()): String {
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
            return stamp + "-" + UUID.randomUUID().toString().substring(0, 8)
        }

        fun nowIso(now: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

        /**
         * 랩 화면의 기록 결과로부터 세트 로그를 만든다.
         * @param samples 기록 구간에서 추론된 프레임들 (detected 가 false 인 프레임은 features 가 비어 있어도 남긴다)
         * @param sampleTimesMs 각 프레임의 타임스탬프(ms). null 이면 샘플 간격으로 합성
         */
        fun build(
            exercise: String,
            samples: List<PoseSample>,
            results: List<RuleResult>,
            rulesVersion: String,
            model: String,
            delegate: String,
            frontCamera: Boolean,
            sampleIntervalMs: Long,
            sampleTimesMs: List<Long>? = null,
            subjectId: String? = null,
            note: String? = null,
            includeVisibility: Boolean = true,
            now: Date = Date(),
            repCount: Int? = null,
            repTimesMs: List<Long>? = null,
            repSignal: String? = null,
            repInvalid: Int? = null,
            repRecords: List<RepRecord>? = null,
            mode: String? = null,
            anchorTMs: Long? = null,
            assessmentEndTMs: Long? = null,
            measurements: List<String> = emptyList(),
            repEngine: RepEngineLog? = null,
            repResets: List<RepResetEvent>? = null,
            repPending: RepPendingState? = null,
            repDropped: List<RepCycle>? = null,
            repRejected: List<RepRejected>? = null,
            repIdentitySwings: List<Float?>? = null,
            repForm: RepFormLog? = null,
            thermalStart: Int? = null,
            thermalChanges: List<ThermalEvent>? = null,
            appVersion: String? = null,
            repUnit: RepUnit? = null,
            repCompleted: Int? = null,
            repHalfPending: Boolean = false,
            validation: Boolean = false,
            /**
             * 검출 프레임마다 좌표(`xy`·`w`·`up`)와 `image` 를 쓴다(§62a 후속 5). 기본은 검증 모드와 같지만 세션은 **항상 켠다** —
             * 알고리즘의 입력이 없으면 출력(피처)만으로는 측정 결함을 못 가른다(12:19 세트: 좌표가 있어서 3D 발목이 발 회전에 흔들리는 걸 알았다).
             * 검증 모드는 이제 횟수 정답 수집용(숫자 숨김·음성·자동 진행)만 맡는다.
             */
            coordinates: Boolean = validation,
        ): SetLog {
            val frames = samples.mapIndexed { i, s ->
                val lm = coordinates && s.detected
                SetLogFrame(
                    tMs = sampleTimesMs?.getOrNull(i) ?: (i * sampleIntervalMs),
                    inferMs = s.inferMs,
                    visibleJointCount = s.visibleJointCount,
                    visibility = if (includeVisibility && s.detected) s.visibility else null,
                    features = if (s.detected) s.features else emptyMap(),
                    xy = if (lm) s.normalizedXy else null,
                    world = if (lm) s.world else null,
                    up = if (lm) floatArrayOf(s.up.x, s.up.y, s.up.z) else null,
                )
            }
            val firstImage = samples.firstOrNull { it.detected }
            val upFromGravity = samples.any { it.upFromGravity }
            val tilt = samples.lastOrNull { it.upFromGravity }?.let { tiltFromScreenUpDegrees(it.up) }
            val view = ViewEstimator.estimate(frames.map { it.features })
            return SetLog(
                setId = newSetId(now),
                createdAtIso = nowIso(now),
                subjectId = subjectId,
                exercise = exercise,
                rulesVersion = rulesVersion,
                model = model,
                delegate = delegate,
                frontCamera = frontCamera,
                upFromGravity = upFromGravity,
                tiltDeg = tilt,
                sampleIntervalMs = sampleIntervalMs,
                frames = frames,
                results = results.map {
                    // 로그의 value 는 절대 좌표로 고정 — 재배치 세트와 비재배치 세트가 섞여도 해석이 갈리지 않는다
                    SetLogResult(it.rule.id, it.verdict.name, it.rawValue ?: it.value, it.sampleCount, it.baselineApplied, if (it.baselineApplied) it.value else null)
                },
                note = note,
                upFlippedFrames = samples.count { it.upFlipped },
                upVerifiedFrames = samples.count { it.upVerified },
                repCount = repCount,
                repTimesMs = repTimesMs,
                repSignal = repSignal,
                repInvalid = repInvalid,
                repMins = repRecords?.map { it.cycleMin },
                repMaxs = repRecords?.map { it.cycleMax },
                repValid = repRecords?.map { it.valid },
                mode = mode,
                anchorTMs = anchorTMs,
                assessmentEndTMs = assessmentEndTMs,
                measurements = measurements,
                viewYawDeg = view?.yawDeg,
                viewR = view?.r,
                viewClass = view?.letter,
                viewFrames = view?.frames,
                repEngine = repEngine,
                repResets = repResets,
                repPending = repPending,
                repDropped = repDropped,
                repRejected = repRejected,
                repIdentitySwings = repIdentitySwings,
                repForm = repForm,
                thermalStart = thermalStart,
                thermalChanges = thermalChanges,
                appVersion = appVersion,
                repUnit = repUnit,
                repCompleted = repCompleted,
                repHalfPending = repHalfPending,
                validation = validation,
                imageWidth = if (coordinates) firstImage?.imageWidth else null,
                imageHeight = if (coordinates) firstImage?.imageHeight else null,
            )
        }
    }
}

/** 의존성 없는 JSON 직렬화 (문자열 이스케이프, NaN/Inf → null). */
object SetLogJson {

    fun encode(log: SetLog): String {
        val sb = StringBuilder(16 * 1024)
        sb.append('{')
        field(sb, "schema", SetLog.SCHEMA)
        field(sb, "set_id", log.setId)
        field(sb, "created_at", log.createdAtIso)
        field(sb, "subject_id", log.subjectId)
        field(sb, "exercise", log.exercise)
        field(sb, "rules_version", log.rulesVersion)
        field(sb, "model", log.model)
        field(sb, "delegate", log.delegate)
        sb.append("\"front_camera\":").append(log.frontCamera).append(',')
        sb.append("\"up_from_gravity\":").append(log.upFromGravity).append(',')
        sb.append("\"tilt_deg\":").append(num(log.tiltDeg)).append(',')
        sb.append("\"sample_interval_ms\":").append(log.sampleIntervalMs).append(',')
        sb.append("\"up_flipped_frames\":").append(log.upFlippedFrames).append(',')
        sb.append("\"up_verified_frames\":").append(log.upVerifiedFrames).append(',')
        field(sb, "note", log.note)
        if (log.mode != null) field(sb, "mode", log.mode)
        if (log.appVersion != null) field(sb, "app_version", log.appVersion)
        // 검증 모드(spec §61)는 키 하나 — 좌표·이미지 크기는 검증 모드와 무관하게 좌표를 남긴 세트면 쓴다(§62a 후속 5, 세션은 항상)
        if (log.validation) sb.append("\"validation\":true,")
        if (log.imageWidth != null && log.imageHeight != null)
            sb.append("\"image\":{\"w\":").append(log.imageWidth).append(",\"h\":").append(log.imageHeight).append("},")
        sb.append("\"measurements\":[")
        log.measurements.forEachIndexed { i, value -> if (i > 0) sb.append(','); str(sb, value) }
        sb.append("],")
        if (log.assessmentEndTMs != null) { sb.append("\"assessment_end_t_ms\":").append(log.assessmentEndTMs).append(',') }
        if (log.anchorTMs != null) { sb.append("\"anchor_t_ms\":").append(log.anchorTMs).append(',') }
        // 촬영 방향 추정 (spec §33) — 없으면 필드 부재 (§33 이전 로그·바닥 종목과 구분)
        if (log.viewClass != null) {
            sb.append("\"view\":{")
            sb.append("\"yaw_deg\":").append(num(log.viewYawDeg, 2)).append(',')
            sb.append("\"r\":").append(num(log.viewR, 3)).append(',')
            field(sb, "class", log.viewClass)
            sb.append("\"frames\":").append(log.viewFrames ?: 0)
            sb.append("},")
        }
        // 열 상태 (spec §58) — 세트 첫 프레임 시점 값 + 세트 중 변화. 없으면 필드 부재 (이전 로그·API 29 미만)
        if (log.thermalStart != null) {
            sb.append("\"thermal\":{")
            sb.append("\"start\":").append(log.thermalStart).append(',')
            sb.append("\"changes\":[")
            log.thermalChanges.orEmpty().forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                sb.append("{\"t_ms\":").append(e.tMs).append(",\"status\":").append(e.status).append('}')
            }
            sb.append("]},")
        }
        // 자동 렙 카운트 — 카운터가 돌았던 세트만 기록 (미적용 세트와 구분: 필드 부재 = 미적용)
        if (log.repCount != null) {
            sb.append("\"reps\":{")
            sb.append("\"count\":").append(log.repCount).append(',')
            sb.append("\"invalid\":").append(log.repInvalid ?: 0).append(',')
            field(sb, "signal", log.repSignal)
            sb.append("\"t_ms\":[")
            log.repTimesMs.orEmpty().forEachIndexed { i, t -> if (i > 0) sb.append(','); sb.append(t) }
            sb.append(']')
            // 렙별 극값·ROM 판정 (spec §29) — t_ms 와 같은 순서. 구버전 로그에는 없다.
            log.repMins?.let { v ->
                sb.append(",\"min\":[")
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(num(x)) }
                sb.append(']')
            }
            log.repMaxs?.let { v ->
                sb.append(",\"max\":[")
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(num(x)) }
                sb.append(']')
            }
            log.repValid?.let { v ->
                sb.append(",\"valid\":[")
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x?.toString() ?: "null") }
                sb.append(']')
            }
            // 표시 단위 (사용자 결정 2026-09-24) — 위의 수·배열은 사이클, completed 는 화면에 보인 횟수. 없으면 키 부재 (이전 로그)
            log.repUnit?.let { u ->
                sb.append(",\"unit\":"); str(sb, u.key)
                sb.append(",\"cycles_per_rep\":").append(u.cyclesPerRep)
                log.repCompleted?.let { sb.append(",\"completed\":").append(it) }
                if (log.repHalfPending) sb.append(",\"half_pending\":true")
            }
            // spec §58 단계 0 — 카운터 구성·리셋·미완 후보. 없으면 키 부재 (이전 로그)
            log.repEngine?.let { e ->
                sb.append(",\"engine\":"); str(sb, e.engine)
                sb.append(",\"config\":{")
                sb.append("\"feature\":"); str(sb, e.feature)
                sb.append(",\"min_amp\":").append(num(e.minAmp))
                sb.append(",\"refractory_ms\":").append(e.refractoryMs)
                sb.append(",\"max_gap_ms\":").append(e.maxGapMs)
                sb.append(",\"complete_on_return\":").append(e.completeOnReturn)
                e.polarity?.let { sb.append(",\"polarity\":"); str(sb, it) }
                e.returnFraction?.let { sb.append(",\"return_fraction\":").append(num(it)) }
                e.firstPairWindowMs?.let {
                    // 시작 확정 구성(새 코어만) — 이후 반복의 시간 창은 null(진폭 비만)도 값이라 명시해 적는다
                    sb.append(",\"first_pair_window_ms\":").append(it)
                    sb.append(",\"later_window_ms\":").append(e.laterWindowMs?.toString() ?: "null")
                    sb.append(",\"min_ratio\":").append(num(e.minRatio)).append(",\"max_ratio\":").append(num(e.maxRatio))
                }
                e.romDirection?.let { sb.append(",\"rom_direction\":"); str(sb, it) }
                e.romThreshold?.let { sb.append(",\"rom_threshold\":").append(num(it)) }
                e.romTier?.let { sb.append(",\"rom_tier\":"); str(sb, it) }
                // 반복 판별 게이트(spec §62) — 판별 신호가 있는 종목만 키가 있다
                e.identityFeature?.let { f ->
                    sb.append(",\"identity\":{\"feature\":"); str(sb, f)
                    sb.append(",\"min_amp\":").append(num(e.identityMinAmp)).append('}')
                }
                sb.append('}')
            }
            // 판별 게이트가 세지 않은 사이클과 센 사이클의 판별 스윙 — 판별 신호가 있는 종목만(없으면 키 부재)
            log.repRejected?.let { v ->
                sb.append(",\"rejected\":[")
                v.forEachIndexed { i, r ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"t_ms\":").append(r.tMs).append(",\"min\":").append(num(r.min)).append(",\"max\":").append(num(r.max))
                    sb.append(",\"swing\":").append(num(r.identitySwing)).append('}')
                }
                sb.append(']')
            }
            log.repIdentitySwings?.let { v ->
                sb.append(",\"identity_swing\":[")
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(num(x)) }
                sb.append(']')
            }
            log.repResets?.let { v ->
                sb.append(",\"resets\":[")
                v.forEachIndexed { i, r ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"t_ms\":").append(r.tMs)
                    sb.append(",\"after_t_ms\":").append(r.afterTMs?.toString() ?: "null")
                    sb.append(",\"reason\":"); str(sb, r.reason); sb.append('}')
                }
                sb.append(']')
            }
            // 세지 않은 후보 — 새 코어만. 객체가 있으면 안쪽의 null 은 "새 코어였고 그 후보는 없었다" 는 뜻이다
            log.repPending?.let { p ->
                sb.append(",\"pending\":{\"unconfirmed\":")
                val u = p.unconfirmed
                if (u == null) sb.append("null") else cycle(sb, u)
                sb.append(",\"in_progress\":")
                val c = p.inProgress
                if (c == null) sb.append("null") else {
                    sb.append("{\"start_t_ms\":").append(c.startMs)
                    sb.append(",\"min\":").append(num(c.min)).append(",\"max\":").append(num(c.max)).append('}')
                }
                sb.append(",\"dropped\":[")
                log.repDropped.orEmpty().forEachIndexed { i, d -> if (i > 0) sb.append(','); cycle(sb, d) }
                sb.append("]}")
            }
            sb.append("},")
        }
        // 반복별 자세 검사(spec §62a) — 평가기가 있는 종목만. reps 블록 뒤, frames 앞
        log.repForm?.let { sb.append("\"rep_form\":").append(repForm(it)).append(',') }
        sb.append("\"frames\":[")
        log.frames.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"t_ms\":").append(f.tMs).append(',')
            sb.append("\"infer_ms\":").append(f.inferMs).append(',')
            sb.append("\"visible\":").append(f.visibleJointCount).append(',')
            sb.append("\"vis\":")
            val vis = f.visibility
            if (vis == null) sb.append("null") else {
                sb.append('[')
                vis.forEachIndexed { k, v -> if (k > 0) sb.append(','); sb.append(num(v, 3)) }
                sb.append(']')
            }
            sb.append(',')
            floats(sb, "xy", f.xy)
            floats(sb, "w", f.world)
            floats(sb, "up", f.up)
            sb.append("\"features\":{")
            var first = true
            for ((k, v) in f.features) {
                if (!first) sb.append(',')
                first = false
                str(sb, k); sb.append(':').append(num(v))
            }
            sb.append("}}")
        }
        sb.append("],")
        sb.append("\"results\":[")
        log.results.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            field(sb, "rule_id", r.ruleId)
            field(sb, "verdict", r.verdict)
            sb.append("\"value\":").append(num(r.value)).append(',')
            sb.append("\"n\":").append(r.sampleCount).append(',')
            sb.append("\"baseline_applied\":").append(r.baselineApplied).append(',')
            sb.append("\"value_rel\":").append(num(r.valueRel))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 발화한 사이클 하나 — 시각은 호출 쪽이 세트 상대로 바꿔 넘긴다. */
    /** `rep_form` 블록(spec §62a) — 인코딩은 [RepFormLog.toJson] 하나다(재생기도 같은 함수를 쓴다). */
    fun repForm(log: RepFormLog): String = log.toJson()

    private fun cycle(sb: StringBuilder, c: RepCycle) {
        sb.append("{\"t_ms\":").append(c.tMs).append(",\"start_t_ms\":").append(c.startMs)
        sb.append(",\"min\":").append(num(c.min)).append(",\"max\":").append(num(c.max))
        sb.append(",\"by_redescent\":").append(c.byRedescent).append('}')
    }

    private fun field(sb: StringBuilder, key: String, value: String?) {
        str(sb, key); sb.append(':')
        if (value == null) sb.append("null") else str(sb, value)
        sb.append(',')
    }

    /** 검증 모드 좌표 배열 — null 이면 키를 쓰지 않는다. 소수 4자리, NaN → null. */
    private fun floats(sb: StringBuilder, key: String, v: FloatArray?) {
        if (v == null) return
        sb.append('"').append(key).append("\":[")
        v.forEachIndexed { k, x -> if (k > 0) sb.append(','); sb.append(num(x, 4)) }
        sb.append("],")
    }

    internal fun num(v: Float?, decimals: Int = 5): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "null"
        // 고정 소수: 과학적 표기/지역화(콤마) 방지
        val s = String.format(Locale.US, "%.${decimals}f", v)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
    }

    internal fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/**
 * 설치 단위 익명 수행자 ID — 재보정에서 **GroupKFold 그룹**으로 쓴다.
 * 같은 사람의 세트가 학습/검증에 갈라져 들어가면 성능이 부풀려지므로, 사람 구분자가 반드시 필요하다.
 * 기기·계정과 무관한 난수라 개인 식별 정보가 아니다.
 */
object SubjectId {
    private const val PREFS = "trex_posture"
    private const val KEY = "subject_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = "s-" + UUID.randomUUID().toString().substring(0, 8)
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}

/**
 * 세트 로그 저장소: `<externalFilesDir>/posture_logs/sets-yyyyMMdd.jsonl` 에 한 줄씩 추가.
 * 외부 앱 전용 저장소라 권한이 필요 없고, `adb pull` 또는 공유 시트로 꺼내 재보정 도구에 넣는다.
 */
class SetLogStore(private val dir: File) {

    constructor(context: Context) : this(File(context.getExternalFilesDir(null) ?: context.filesDir, "posture_logs"))

    val directory: File get() = dir

    fun append(log: SetLog, now: Date = Date()): File {
        dir.mkdirs()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val file = File(dir, "sets-$day.jsonl")
        file.appendText(SetLogJson.encode(log) + "\n", Charsets.UTF_8)
        return file
    }

    fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    fun totalSets(): Int = files().sumOf { f -> f.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } } }

    fun totalBytes(): Long = files().sumOf { it.length() }

    fun clear() {
        files().forEach { it.delete() }
    }
}
