package com.example.trex_kotlin.posture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대사 점검(§90) — 자세 교정 서비스가 보는 모든 반복 검사가 말할 문장을 다 갖췄는지. 빠지면 "조건을 벗어났어요"·"무릎 낮음" 같은
 * 일반 문장으로 떨어지거나(판정 방향별 문장), 쿨다운 안의 짧은 단서가 부위 꼬리표가 되거나, 처음부터 틀린 출발 알림이 일반 문장이 된다.
 */
class RepFormLinesTest {
    private val relative = setOf(RepFormRef.START_DELTA, RepFormRef.START_RATIO, RepFormRef.SET_MIN_DELTA, RepFormRef.FIRST_REPS_DELTA, RepFormRef.SET_LOW_DELTA)

    @Test
    fun everyCheckHasASentenceForEachDirectionAFixAndABriefCue() {
        for ((ex, checks) in RepFormSpecs.byExercise) for (c in checks) {
            if (c.hi != null) assertTrue("$ex ${c.id}: 높은 쪽 문장", !c.highText.isNullOrBlank())
            if (c.lo != null) assertTrue("$ex ${c.id}: 낮은 쪽 문장", !c.lowText.isNullOrBlank())
            assertTrue("$ex ${c.id}: 고치는 말", c.fix.isNotBlank())
            assertTrue("$ex ${c.id}: 짧은 단서", !c.cue.isNullOrBlank())
            if (c.liveHoldFrames != null) assertTrue("$ex ${c.id}: 유지 자세 문장", !c.liveText.isNullOrBlank())
        }
    }

    @Test
    fun everyShipCheckWithAPersonalReferenceHasAStartNotice() {
        // 본인 기준 검사는 처음부터 틀리면 그 틀림이 기준이 된다 — 모집단 기준 범위 밖 출발을 알리는 전용 문장이 있어야 한다
        for ((ex, checks) in RepFormSpecs.byExercise) for (c in checks) {
            if (c.ship && c.ref in relative) assertTrue("$ex ${c.id}: 처음부터 알림 문장", !c.noticeText.isNullOrBlank())
        }
    }
}
