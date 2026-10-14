package com.example.trex_kotlin.posture

/** 요청한 네 종목의 교정 정책. 검사 등급과 원시 반복 기록은 그대로 보존한다. */
object RequestedFormCoaching {
    const val VERSION = "requested_form_v5_beta"
    private val exercises = setOf("굿모닝", "푸시업", "사이드 레터럴 레이즈", "프런트 레이즈")
    fun isTarget(exercise: String) = exercise in exercises

    private fun issue(o: RepFormOutcome) = isTarget(o.check.exercise) && o.check.status != RuleStatus.EXCLUDE &&
        o.verdict == Verdict.VIOLATION && o.direction != null && o.value?.isFinite() == true

    private fun squatPeer(o: RepFormOutcome): String? {
        if(o.check.exercise!="굿모닝") return null
        val f=o.check.feature
        return when {
            f.startsWith("goodmorning_knee_side_") -> "goodmorning_hip_height_"+f.takeLast(1)
            f.startsWith("goodmorning_hip_height_") -> "goodmorning_knee_side_"+f.takeLast(1)
            else -> null
        }
    }

    // 같은 반복 하단·같은 다리에서 두 조건이 모두 확인되어야 스쿼트형 동작으로 제외한다.
    private fun blocks(rep: RepFormRep, o: RepFormOutcome): Boolean {
        if(!issue(o) || !o.gate || !o.check.gates) return false
        val peer=squatPeer(o) ?: return true
        return rep.outcomes.any { it.check.exercise=="굿모닝" && it.check.feature==peer &&
            issue(it) && it.gate && it.check.gates }
    }

    fun firstIssue(rep: RepFormRep?): RepFormOutcome? {
        if(rep==null) return null
        val outcomes=rep.outcomes.filter { issue(it) && (squatPeer(it)==null || blocks(rep,it)) }
        return outcomes.firstOrNull { blocks(rep,it) } ?: outcomes.firstOrNull()
    }

    /** 사용자 요청에 따른 코치 모드 적용. beta를 검증 완료(ship)로 바꾸지는 않는다. */
    fun excluded(rep: RepFormRep): Boolean = rep.outcomes.any { blocks(rep,it) }

    fun observed(rep: RepFormRep) = rep.outcomes.any {
        isTarget(it.check.exercise) && it.check.status != RuleStatus.EXCLUDE && it.verdict != Verdict.ABSTAIN
    }

    fun counted(ex: String, coach: Boolean, rawNotShort: Int, rawShort: Int, excludeShort: Boolean,
                legacyIncorrect: Int, requestedExcluded: Int): Int =
        (rawNotShort + (if (excludeShort) 0 else rawShort) -
            if (isTarget(ex)) { if (coach) requestedExcluded else 0 } else legacyIncorrect).coerceAtLeast(0)

    fun message(rep: RepFormRep?, brief: Boolean = false): String? {
        val issue = firstIssue(rep) ?: return null
        val check = issue.check
        val line = if (squatPeer(issue)!=null) {
            if(brief) "무릎 굽힘과 골반 하강" else
                "무릎이 크게 굽혀지면서 엉덩이가 아래로 내려갔어요. 주저앉지 말고 엉덩이를 뒤로 보내며 숙여 주세요"
        } else if (brief) check.cue ?: check.bodyPart else
            check.text(issue.direction!!).trim().trimEnd('.') + ". " + check.fix.trim().trimEnd('.')
        return line + if (rep != null && excluded(rep)) ". 이 회는 세지 않았어요." else "."
    }

    fun summaryLines(summary: RepFormSummary, coach: Boolean): List<String> = buildList {
        if (summary.checks.none { isTarget(it.exercise) }) return@buildList
        add("교정 기준 확인 중 · 실기기 정확도 검증 전")
        val excluded = summary.reps.count(::excluded)
        if (excluded > 0) add(if (coach) "자세 교정 대상 ${excluded}회" else "기록 모드 · 자세 교정 대상 ${excluded}회도 기록했어요")
        val missing = summary.reps.count { !observed(it) }
        if (missing > 0) add("자세를 확인하지 못한 반복 ${missing}회 · 정상 판정에 포함하지 않아요")
    }
}
