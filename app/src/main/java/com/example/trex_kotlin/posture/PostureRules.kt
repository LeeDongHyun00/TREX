package com.example.trex_kotlin.posture

import android.content.Context
import org.json.JSONObject

/**
 * rules_mp_v0 규칙셋 로더/평가기 (spec §7).
 *
 * 규칙은 assets/posture/rules_mp_v0.json 에서 읽는다. 각 규칙은
 * "집계 통계값이 임계값을 넘으면 위반" 형태의 단일 피처 규칙이다.
 */

enum class RuleStatus { SHIP, BETA, EXCLUDE;
    companion object {
        fun from(s: String): RuleStatus = when (s) {
            "ship" -> SHIP
            "beta" -> BETA
            else -> EXCLUDE
        }
    }
}

enum class Verdict { OK, VIOLATION, ABSTAIN }

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
) {
    val violationText: String get() = "$feature $op ${fmt(threshold)}"

    fun isViolated(value: Float): Boolean = if (op == "<") value < threshold else value > threshold

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
    val value: Float?,
    val sampleCount: Int,
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
            // 하위유형이 있으면 그것이 정본. [all] 은 다른 하위유형이 있을 때만 중복이므로 제거 (spec §7)
            .let { list ->
                val hasSpecific = list.any { it.subtype != null && it.subtype != "all" }
                if (hasSpecific) list.filter { it.subtype != "all" } else list
            }
            .sortedWith(compareBy({ it.status }, { -it.cvAuc }))

    /** 집계 결과에 규칙을 적용. minFrames 미만이면 유보. */
    fun evaluate(
        exercise: String,
        agg: FeatureAggregator,
        includeBeta: Boolean = true,
        minFrames: Int = 8,
    ): List<RuleResult> = rulesFor(exercise, includeBeta).map { rule ->
        val n = agg.count(rule.baseFeature)
        val value = if (n >= minFrames) agg.stat(rule.baseFeature, rule.stat) else null
        val verdict = when {
            value == null -> Verdict.ABSTAIN
            rule.isViolated(value) -> Verdict.VIOLATION
            else -> Verdict.OK
        }
        RuleResult(rule, verdict, value, n)
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
                out += PostureRule(
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
