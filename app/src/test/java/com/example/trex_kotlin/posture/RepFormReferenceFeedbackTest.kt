package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 참고 문구의 운동 범위·판정 등급·표시 내용. */
class RepFormReferenceFeedbackTest {
    private val targets = listOf("굿모닝", "프런트 레이즈", "사이드 레터럴 레이즈", "푸시업")

    private fun check(ex: String = "프런트 레이즈") =
        RepFormSpecs.byExercise.getValue("프런트 레이즈").first().copy(
            id = "repform|$ex|테스트", exercise = ex, status = RuleStatus.BETA, gates = false,
            highText = "팔꿈치 굽힘이 반복 중 달라졌어요", lowText = "상체가 뒤로 기울었어요",
            fix = "움직이는 동안 자세를 유지해 주세요")

    private fun outcome(c: RepFormCheck = check(), verdict: Verdict = Verdict.VIOLATION,
                        direction: FormDirection? = FormDirection.HIGH) =
        RepFormOutcome(c, verdict, 40f, 40f, null, direction, 8,
            abstainReason = if (verdict == Verdict.ABSTAIN) "관측 부족" else null)

    private fun rep(vararg outcomes: RepFormOutcome) = RepFormRep(1, 3000L, outcomes.toList())

    @Test fun theFourRequestedExercisesCanShowTheBetaObservationAndFix() {
        for (ex in targets) {
            val c = check(ex)
            val message = RepFormSpecs.referenceMessage(ex, rep(outcome(c)))
            assertNotNull(ex, message)
            assertTrue(message!!.startsWith("참고 · "))
            assertTrue(message.contains(c.highText!!))
            assertTrue(message.contains(c.fix))
        }
    }

    @Test fun exercisesOutsideTheRequestedScopeDoNotGainAReferenceBanner() {
        for (ex in listOf("바벨 스쿼트", "덤벨 컬", "런지", "바벨 컬", "바벨 런지", "플랭크", "니푸쉬업", "없는 운동")) {
            assertNull(ex, RepFormSpecs.referenceMessage(ex, rep(outcome(check(ex)))))
        }
    }

    @Test fun anOkayRepetitionDoesNotProduceAnUnverifiedNormalLabel() {
        for (ex in targets) assertNull(RepFormSpecs.referenceMessage(ex,
            rep(outcome(check(ex), Verdict.OK, null))))
    }

    @Test fun anUnobservedRepetitionDoesNotProduceAReferenceOrNormalLabel() {
        for (ex in targets) {
            assertNull(RepFormSpecs.referenceMessage(ex, rep()))
            assertNull(RepFormSpecs.referenceMessage(ex, rep(outcome(check(ex), Verdict.ABSTAIN, null))))
        }
    }

    @Test fun lowDirectionUsesItsOwnObservation() {
        val c = check()
        val message = RepFormSpecs.referenceMessage(c.exercise, rep(outcome(c, direction = FormDirection.LOW)))!!
        assertTrue(message.contains(c.lowText!!))
        assertTrue(message.contains(c.fix))
        assertFalse(message.contains(c.highText!!))
    }

    @Test fun aShipViolationIsNotRelabeledAsAReference() {
        val c = check().copy(status = RuleStatus.SHIP, gates = true)
        assertNull(RepFormSpecs.referenceMessage(c.exercise, rep(outcome(c))))
    }

    @Test fun anEarlierShipOrOkayOutcomeDoesNotHideTheBetaViolation() {
        val c = check()
        val message = RepFormSpecs.referenceMessage(c.exercise, rep(
            outcome(c.copy(id = c.id + "|ship", status = RuleStatus.SHIP, highText = "검증된 지적")),
            outcome(c.copy(id = c.id + "|okay"), Verdict.OK, null),
            outcome(c)))!!
        assertTrue(message.contains(c.highText!!))
        assertTrue(message.contains(c.fix))
        assertFalse(message.contains("검증된 지적"))
    }

    @Test fun theNextOkayOrUnobservedRepetitionClearsTheMessage() {
        val c = check()
        assertNotNull(RepFormSpecs.referenceMessage(c.exercise, rep(outcome(c))))
        assertNull(RepFormSpecs.referenceMessage(c.exercise, rep(outcome(c, Verdict.OK, null))))
        assertNull(RepFormSpecs.referenceMessage(c.exercise, rep(outcome(c, Verdict.ABSTAIN, null))))
    }
}
