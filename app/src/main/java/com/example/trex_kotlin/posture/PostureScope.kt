package com.example.trex_kotlin.posture

/**
 * 한 종목에서 이 앱이 실제로 보는 것 / 검증 중인 것 / 못 보는 것. 조건(condition) 단위로 묶는다.
 *
 * 왜 필요한가: 바벨 데드리프트는 '척추의 중립' 규칙이 전부 exclude 라, 사용자가 허리를 말아도 ship 2규칙(바 궤적·
 * 무릎 방향)만 통과하면 리포트가 "이번 세트 깨끗했어요" 라고 말한다. 안 본 것을 본 것처럼 말하는 거짓 안심이다.
 * 그래서 판정 결과보다 먼저 **평가 범위**를 밝힌다.
 *
 * 목록은 조건명이 아니라 **부위명**(CoachCues 의 bodyPart) 으로 압축한다 — "발과 무릎의 방향 일치" 보다
 * "무릎" 이 한 문장에 들어가고 귀로도 들린다. 같은 부위가 두 등급에 걸치면 높은 등급만 남긴다
 * ("무릎을 봐요. 무릎은 못 봐요." 같은 자기모순 방지).
 */
data class PostureScope(
    val exercise: String,
    /** ship 규칙이 하나라도 있는 조건 — 판정한다. */
    val watched: List<String>,
    /** beta 규칙만 있는 조건 — 화면에 '참고'로만 남고 음성은 침묵한다. */
    val provisional: List<String>,
    /** 규칙이 전부 exclude 인 조건 — 이 앱이 못 본다. */
    val blind: List<String>,
) {
    val hasAnyJudgement: Boolean get() = watched.isNotEmpty()

    /** 전부 검증 중(바닥 종목 8개) — 라이브 음성이 자세를 지적하지 않는다. */
    val provisionalOnly: Boolean get() = watched.isEmpty() && provisional.isNotEmpty()

    /** 세트 시작 안내의 둘째 문장. 없으면 null. 짧게 — 항목은 각 최대 2개, 끝에 마침표. */
    val startLine: String? = when {
        watched.isNotEmpty() -> buildString {
            val seen = watched.take(2).joinToString("·")
            append(seen).append(objectParticle(seen)).append(" 봐요.")
            if (blind.isNotEmpty()) {
                val miss = blind.first()
                append(" ").append(miss).append(topicParticle(miss)).append(" 못 봐요.")
            }
        }
        provisional.isNotEmpty() -> "이 종목은 아직 검증 중이라 자세 지적 없이 횟수와 촬영 상태만 알려드려요."
        // 규칙이 전부 exclude — 말할 판정이 없다는 사실 자체를 말해야 한다
        blind.isNotEmpty() -> "이 종목은 자세를 판정할 항목이 없어요. 횟수와 촬영 상태만 알려드려요."
        else -> null
    }

    /** 운동 카드 부제 한 줄. 예 "평가 4 · 검증 중 1 · 못 봄 3" */
    val cardLine: String = buildList {
        if (watched.isNotEmpty()) add("평가 ${watched.size}")
        if (provisional.isNotEmpty()) add("검증 중 ${provisional.size}")
        if (blind.isNotEmpty()) add("못 봄 ${blind.size}")
    }.joinToString(" · ").ifEmpty { "평가 항목 없음" }

    /** 자세 교정을 처음 켤 때 보여 줄 3줄. */
    val introLines: List<String> = listOf(
        "카메라로 관절 위치만 봐요. 영상은 저장하지 않아요.",
        startLine ?: "이 종목에서 볼 수 있는 항목이 없어요.",
        "사람이 보는 것과 다를 수 있어요. 이상하면 세트 뒤에 '아니었어요'로 알려주세요.",
    )

    companion object {
        /**
         * 종목의 전 규칙(exclude 포함 — 로더가 필터하지 않는다)을 조건별로 묶어 등급을 매긴다.
         * 조건의 등급 = 그 조건 규칙들의 **최고** 상태(SHIP > BETA > EXCLUDE): 한 조건에 ship 이 하나라도 있으면
         * 그 조건은 판정된다.
         */
        fun of(ruleSet: PostureRuleSet, exercise: String): PostureScope {
            val watched = ArrayList<String>()
            val provisional = ArrayList<String>()
            val blind = ArrayList<String>()
            // groupBy 는 등장 순서를 지킨다 — 규칙 JSON 순서가 곧 화면 순서
            for ((_, rules) in ruleSet.rules.filter { it.exercise == exercise }.groupBy { it.condition }) {
                val part = bodyPartOf(rules.first())
                val target = when (rules.minOf { it.status }) {
                    RuleStatus.SHIP -> watched
                    RuleStatus.BETA -> provisional
                    RuleStatus.EXCLUDE -> blind
                }
                target += part
            }
            val w = watched.distinct()
            val p = provisional.distinct().filterNot { it in w }
            val b = blind.distinct().filterNot { it in w || it in p }
            return PostureScope(exercise, w, p, b)
        }

        /** 조건의 대표 규칙에서 부위명을 뽑는다. 카탈로그에 없는 조건은 cueFor 폴백이 조건명 자체를 준다. */
        private fun bodyPartOf(rule: PostureRule): String =
            CoachCues.cueFor(rule, Direction.PRIMARY).bodyPart.takeIf { it.isNotBlank() } ?: rule.condition

        /** 받침이 있으면 '을/은', 없으면 '를/는'. 한글이 아니면 보수적으로 받침 있음으로 본다. */
        private fun hasFinalConsonant(word: String): Boolean {
            val c = word.trimEnd().lastOrNull() ?: return false
            if (c.code < 0xAC00 || c.code > 0xD7A3) return true
            return (c.code - 0xAC00) % 28 != 0
        }

        internal fun objectParticle(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

        internal fun topicParticle(word: String): String = if (hasFinalConsonant(word)) "은" else "는"
    }
}
