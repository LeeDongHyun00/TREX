package com.example.trex_kotlin.posture

import android.content.Context
import org.json.JSONObject

/**
 * rules_mp_v0 규칙셋 로더/평가기 (spec §7).
 *
 * 규칙은 assets/posture/rules_mp_v0.json 에서 읽는다. 각 규칙은
 * "집계 통계값이 임계값을 넘으면 위반" 형태의 단일 피처 규칙이다.
 */

// RuleStatus·Verdict·Direction 은 RuleTypes.kt — 안드로이드 의존이 없는 파일이라 재생기(replay-jvm)도 컴파일한다(§62a RepForm 이 쓴다).

/**
 * 반대측 가드 (spec §23, BIDIRECTIONAL.md). 같은 피처의 정상 분포 반대쪽 경계(med±3·MAD, MP 스케일).
 * validated=false 는 그 방향의 라벨이 없어 검출률이 미보증이라는 뜻 — 오탐률만 통제된 보수적 경계다.
 */
data class OppositeGuard(
    val op: String,
    val threshold: Float,
    val desc: String,
    val validated: Boolean,
    val nNorm: Int,
) {
    fun isViolated(value: Float): Boolean = if (op == "<") value < threshold else value > threshold
}

data class PostureRule(
    val id: String,
    val exercise: String,
    val condition: String,
    val subtype: String?,
    val status: RuleStatus,
    val reason: String?,
    val feature: String,
    val baseFeature: String,
    val stat: String,
    val family: String,
    val op: String,
    val threshold: Float,
    val view: String,
    val viewDesc: String,
    val cvAuc: Float,
    val cvBalacc: Float,
    val sampleN: Int,
    val mirrorSafe: Boolean,
    val cautions: List<String>,
    /** 개인 기준선(정자세 k세트 중앙값) 보정이 검증된 규칙인지 (spec §15/§16, rules JSON personal_baseline.eligible). */
    val baselineEligible: Boolean = false,
    /** 기준선을 뺀 값(value − baseline)에 적용할 임계값 (같은 op). null 이면 기준선 보정 불가. */
    val baselineThresholdRel: Float? = null,
    /** JSON personal_baseline.required — 기준선 없으면 판정 보류 (spec §28e). */
    val baselineRequired: Boolean = false,
    /** 기준선에 필요한 정자세 세트 수. */
    val baselineK: Int = 3,
    /** 연구(GT)에서 측정된 기준선 보정 이득(AUC Δ). */
    val baselineGain: Float? = null,
    /** 반대 방향 가드 — 있으면 기본 방향이 정상일 때 반대측도 검사한다. */
    val oppositeGuard: OppositeGuard? = null,
    /**
     * 이 규칙을 판정해도 되는 촬영 방향(추정기 등급 문자, spec §33 JSON `views_ok`). 비어 있으면 게이팅 없음(종전 동작).
     * 연구가 5뷰 카메라의 (클립,뷰)를 **추정기가 낸 등급**으로 나눠 출시 임계값의 오탐률·검출률·균형정확도를 잰 결과다 —
     * 런타임이 보는 것도 추정기 출력이므로 같은 단위여야 자기 일관적이다. 표본이 없는 등급(옆 등)은 목록에 없다 = 유보.
     */
    val viewsOk: Set<String> = emptySet(),
    val kind: String = "window",
    val holdConfig: HoldConfig? = null,
    val repConfig: RepRuleConfig? = null,
    val alignmentConfig: AlignmentConfig? = null,
) {
    val violationText: String get() = "$feature $op ${fmt(threshold)}"

    /** 기준선 보정 판정이 가능한 규칙 (eligible + 상대 임계값 보유). */
    val supportsBaseline: Boolean get() = baselineEligible && baselineThresholdRel != null

    /**
     * 기준선이 **필수**인 규칙 (spec §28e). 임계값이 기기 분포 한가운데에 놓여 raw 판정이
     * 사실상 동전던지기인 경우 — 기준선 없이 판정하면 오탐이 난다(실측: 스쿼트 heel_lift
     * 기기 p10~p90 = 0.506~0.637 인데 AIHub 임계 0.580). 기준선 없으면 ABSTAIN.
     */
    val requiresBaseline: Boolean get() = baselineRequired

    fun isViolated(value: Float): Boolean = if (op == "<") value < threshold else value > threshold

    /** 기준선을 뺀 값으로 판정. 상대 임계값이 없으면 절대 임계값으로 폴백. */
    fun isViolatedRelative(adjusted: Float): Boolean {
        val t = baselineThresholdRel ?: return isViolated(adjusted)
        return if (op == "<") adjusted < t else adjusted > t
    }

    companion object {
        fun fmt(v: Float): String = when {
            kotlin.math.abs(v) >= 100f -> String.format("%.0f", v)
            kotlin.math.abs(v) >= 1f -> String.format("%.2f", v)
            else -> String.format("%.4f", v)
        }
    }
}

data class RuleResult(
    val rule: PostureRule,
    val verdict: Verdict,
    /** 판정에 쓴 값 — 기준선이 적용됐으면 (원값 − 기준선). */
    val value: Float?,
    val sampleCount: Int,
    /** 개인 기준선을 빼고 상대 임계값으로 판정했는지. */
    val baselineApplied: Boolean = false,
    /** 기준선 적용 전 원값 (baselineApplied 가 false 면 value 와 같다). */
    val rawValue: Float? = value,
    /** 위반일 때 어느 방향인지. OPPOSITE 는 반대측 가드(원값 기준) 위반. 위반이 아니면 null. */
    val direction: Direction? = null,
    /** 유보(ABSTAIN)한 이유 — 짧은 명사구 ("촬영 방향 · 뒤", "기준선 필요"). 피처가 없어서 유보한 경우는 null. */
    val abstainReason: String? = null,
    val measurement: String? = null,
)

class PostureRuleSet(
    val version: String,
    val generated: String,
    val rules: List<PostureRule>,
) {
    /** 활성(ship + beta) 규칙이 있는 종목 목록. */
    val exercises: List<String> = rules
        .filter { it.status != RuleStatus.EXCLUDE }
        .map { it.exercise }
        .distinct()
        .sorted()

    fun rulesFor(exercise: String, includeBeta: Boolean = true): List<PostureRule> =
        rules.filter {
            it.exercise == exercise &&
                (it.status == RuleStatus.SHIP || (includeBeta && it.status == RuleStatus.BETA))
        }
            // 동일 조건·피처·판정 유형·등급의 하위유형만 [all]을 대체한다 (§39).
            // 다른 부위를 재거나 beta인 규칙 때문에 독립 ship 규칙을 숨기면 안 된다.
            .let { list ->
                val specific = list.filter { it.subtype != null && it.subtype != "all" }
                list.filter { rule -> rule.subtype != "all" || specific.none {
                    it.condition == rule.condition && it.feature == rule.feature &&
                        it.kind == rule.kind && it.status == rule.status
                } }
            }
            .sortedWith(compareBy({ it.status }, { -it.cvAuc }))

    /** 개인 기준선이 검증된(eligible + 상대 임계값) 활성 규칙이 있는 종목 — 기준선 설정 UI 대상. */
    val baselineExercises: List<String> = rules
        .filter { it.status != RuleStatus.EXCLUDE && it.supportsBaseline }
        .map { it.exercise }
        .distinct()
        .sorted()

    /** 종목의 기준선 대상 규칙 (활성 + supportsBaseline). 같은 피처를 쓰는 하위유형 중복은 피처 기준으로 합친다. */
    fun baselineRulesFor(exercise: String): List<PostureRule> =
        rulesFor(exercise, includeBeta = true).filter { it.supportsBaseline }.distinctBy { it.feature }

    /** 종목의 기준선에 필요한 feature(base__stat) 목록. */
    fun baselineFeaturesFor(exercise: String): List<String> = baselineRulesFor(exercise).map { it.feature }

    /** 종목의 기준선 세트 수 (규칙들의 k 최댓값, 기본 3). */
    fun baselineSetsFor(exercise: String): Int = baselineRulesFor(exercise).maxOfOrNull { it.baselineK } ?: BaselineCollector.DEFAULT_SETS

    /**
     * 집계 결과에 규칙을 적용. minFrames 미만이면 유보.
     * @param baseline 사용자 기준선 (feature → 값). 주어지고 규칙이 supportsBaseline 이면 (값 − 기준선) 을 상대 임계값과 비교한다.
     */
    fun evaluate(
        exercise: String,
        agg: FeatureAggregator,
        includeBeta: Boolean = true,
        minFrames: Int = 8,
        baseline: Map<String, Float>? = null,
    ): List<RuleResult> {
        // §33: 이 창의 촬영 방향. 프레임이 모자라거나 방향이 일관되지 않으면(UNKNOWN) 게이팅하지 않는다 = 종전 동작
        val view = ViewEstimator.estimate(agg, minFrames)?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }
        return rulesFor(exercise, includeBeta).map { rule ->
            if (rule.kind != "window") return@map RuleResult(rule, Verdict.ABSTAIN, null, 0, abstainReason = "시간·반복 측정 필요")
            val n = agg.count(rule.baseFeature)
            val raw = if (n >= minFrames) agg.stat(rule.baseFeature, rule.stat) else null
            val b = baseline?.get(rule.feature)
            val useBaseline = raw != null && b != null && rule.supportsBaseline
            val value = if (useBaseline) raw!! - b!! else raw
            val viewBlocked = view != null && rule.viewsOk.isNotEmpty() && view.letter !in rule.viewsOk
            var reason: String? = null
            var verdict = when {
                value == null -> Verdict.ABSTAIN
                // §33: 이 규칙이 검증된 촬영 방향이 아니다 — 판정하지 않는다 (뒤·옆·반대편에서 그대로 점수에 들어가던 결함)
                viewBlocked -> { reason = "촬영 방향 · " + view!!.cls.label; Verdict.ABSTAIN }
                // §28e: 기준선 필수 규칙은 기준선 없이 판정하지 않는다 — raw 임계가 기기 분포 중앙이라 오탐
                rule.requiresBaseline && !useBaseline -> { reason = "기준선 필요"; Verdict.ABSTAIN }
                useBaseline -> if (rule.isViolatedRelative(value)) Verdict.VIOLATION else Verdict.OK
                rule.isViolated(value) -> Verdict.VIOLATION
                else -> Verdict.OK
            }
            var direction: Direction? = if (verdict == Verdict.VIOLATION) Direction.PRIMARY else null
            // 반대측 가드: 기본 방향이 정상일 때만, 기준선 없이 **원값**으로 검사(가드는 모집단 정상 분포 경계라 개인 보정 대상이 아님)
            if (verdict == Verdict.OK && raw != null) {
                rule.oppositeGuard?.let { g ->
                    if (g.isViolated(raw)) {
                        verdict = Verdict.VIOLATION
                        direction = Direction.OPPOSITE
                    }
                }
            }
            RuleResult(rule, verdict, value, n, baselineApplied = useBaseline, rawValue = raw, direction = direction, abstainReason = reason)
        }
    }

    /**
     * 반복별 검사(`RepFormSpecs`, spec §62a)를 규칙으로 뒤에 붙인 사본 — 범위 문장·리포트 행·상태 표시용(판정은 `RepFormSummary` 가 채운다).
     * 반복 검사가 **대체하는** 창 규칙(`RepFormSpecs.supersedes` — 스쿼트 무릎 방향 세트 평균)은 beta 로 낮춘다: 음성·점수·헤드라인에서 빠지고
     * 리포트엔 '참고' 로 남는다. 버전은 `…+repform_v0.1` 로 이어 붙는다. AIHub 조건 밖의 항목이 여기 온다(사용자 결정 2026-09-25).
     */
    fun plusRepForm(): PostureRuleSet {
        val demoted = rules.map { r ->
            if (r.id in RepFormSpecs.supersedes && r.status == RuleStatus.SHIP)
                r.copy(status = RuleStatus.BETA, cautions = r.cautions + "§62a: 반복 창 검사(${RepFormSpecs.supersedes.getValue(r.id)})가 판정을 대신한다 — 세트 평균은 서 있는 프레임이 결정해 실기기 오탐")
            else r
        }
        return PostureRuleSet("$version+${RepFormSpecs.VERSION}", generated, demoted + RepFormSpecs.asRules())
    }

    companion object {
        const val ASSET_PATH = "posture/rules_mp_v0.json"

        fun load(context: Context, assetPath: String = ASSET_PATH): PostureRuleSet {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("rules")
            val out = ArrayList<PostureRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cautionsArr = o.optJSONArray("cautions")
                val cautions = buildList {
                    if (cautionsArr != null) for (j in 0 until cautionsArr.length()) add(cautionsArr.getString(j))
                }
                val pb = o.optJSONObject("personal_baseline")
                val og = o.optJSONObject("opposite_guard")?.let { g ->
                    OppositeGuard(
                        op = g.getString("op"),
                        threshold = g.getDouble("threshold").toFloat(),
                        desc = g.optString("desc"),
                        validated = g.optBoolean("validated", false),
                        nNorm = g.optInt("n_norm", 0),
                    )
                }
                out += PostureRule(
                    kind = o.optString("kind", "window"),
                    alignmentConfig = o.optJSONObject("alignment")?.let { a -> AlignmentConfig(
                        a.getDouble("lower").toFloat(), a.getDouble("upper").toFloat(), a.getLong("sustain_ms")) },
                    holdConfig = o.optJSONObject("hold")?.let { h -> HoldConfig(
                        h.getDouble("tol_up").toFloat(), h.getDouble("tol_down").toFloat(),
                        h.getLong("baseline_ms"), h.getLong("min_break_ms")) },
                    repConfig = o.optJSONObject("rep")?.let { r -> RepRuleConfig(
                        r.getString("direction"), r.getDouble("threshold").toFloat(),
                        r.getDouble("max_invalid_frac").toFloat(), r.getInt("min_reps")) },
                    id = o.getString("id"),
                    exercise = o.getString("exercise"),
                    condition = o.getString("condition"),
                    subtype = o.optString("subtype").takeIf { it.isNotEmpty() && it != "null" },
                    status = RuleStatus.from(o.getString("status")),
                    reason = o.optString("reason").takeIf { it.isNotEmpty() && it != "null" },
                    feature = o.getString("feature"),
                    baseFeature = o.getString("base_feature"),
                    stat = o.getString("stat"),
                    family = o.optString("family"),
                    op = o.getString("op"),
                    threshold = o.getDouble("threshold").toFloat(),
                    view = o.optString("view_best_front"),
                    viewDesc = o.optString("view_best_front_desc"),
                    cvAuc = o.optDouble("cv_auc", Double.NaN).toFloat(),
                    cvBalacc = o.optDouble("cv_balacc", Double.NaN).toFloat(),
                    sampleN = o.optInt("n", 0),
                    mirrorSafe = o.optBoolean("mirror_safe", true),
                    cautions = cautions,
                    baselineEligible = pb?.optBoolean("eligible", false) ?: false,
                    baselineRequired = pb?.optBoolean("required", false) ?: false,
                    baselineThresholdRel = pb?.let { if (it.has("threshold_rel") && !it.isNull("threshold_rel")) it.getDouble("threshold_rel").toFloat() else null },
                    baselineK = pb?.optInt("k", BaselineCollector.DEFAULT_SETS) ?: BaselineCollector.DEFAULT_SETS,
                    baselineGain = pb?.let { if (it.has("gain") && !it.isNull("gain")) it.getDouble("gain").toFloat() else null },
                    oppositeGuard = og,
                    viewsOk = o.optJSONArray("views_ok")?.let { a -> buildSet { for (j in 0 until a.length()) add(a.getString(j)) } } ?: emptySet(),
                )
            }
            return PostureRuleSet(
                version = root.optString("version"),
                generated = root.optString("generated"),
                rules = out,
            )
        }
    }
}
