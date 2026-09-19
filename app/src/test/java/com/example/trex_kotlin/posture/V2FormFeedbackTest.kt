package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class V2FormFeedbackTest {
    private fun rule(feature: String = "torso_incl", status: RuleStatus = RuleStatus.SHIP) = PostureRule(
        feature, "바벨 스쿼트", "척추의 중립", null, status, null, "${feature}__mean", feature, "mean", "test",
        ">", 30f, "C", "정면", .8f, .8f, 100, true, emptyList(), viewsOk = setOf("C"))
    private fun feedback(vararg rules: PostureRule) = V2FormFeedback(PostureRuleSet("test", "", rules.toList()), "바벨 스쿼트")
    private var time = 10000L
    private fun feed(f: V2FormFeedback, value: Float = 40f, count: Int = 16, view: Boolean = true, anchor: Boolean = false): List<String> =
        (1..count).mapNotNull {
            time += 200
            f.accept(time, buildMap { put("torso_incl", value); if(view) { put("view_cos",1f);put("view_sin",0f) } }, anchor)
        }

    @Test fun waitsForCompletedMovementAndTwoIndependentWindows() {
        val f=feedback(rule())
        assertTrue(feed(f,count=40).isEmpty());assertNull(V2FormFeedback.score(f.current,CoachMode.COACH))
        feed(f,count=1,anchor=true)
        assertTrue(feed(f,count=8).isEmpty());assertNull(V2FormFeedback.score(f.current,CoachMode.COACH))
        val cues=feed(f,count=8)
        assertEquals(1,cues.size);assertTrue(cues.single().contains("몸통 기울기"))
        assertFalse(cues.single().contains("척추"));assertFalse(cues.single().contains("말려"))
        assertEquals(0,V2FormFeedback.score(f.current,CoachMode.COACH))
    }

    @Test fun validNormalWindowsScoreButUnknownViewDoesNot() {
        val f=feedback(rule());feed(f,count=1,anchor=true);feed(f,10f)
        assertEquals(100,V2FormFeedback.score(f.current,CoachMode.COACH))
        assertNull(V2FormFeedback.score(f.current,CoachMode.TRACK))
        f.interrupt();feed(f,count=1,anchor=true);feed(f,10f,view=false)
        assertNull(V2FormFeedback.score(f.current,CoachMode.COACH))
    }

    @Test fun missingFramesAndInterruptRequireNewAnchorAndDoNotErasePastViolation() {
        val f=feedback(rule());feed(f,count=1,anchor=true);feed(f)
        f.interrupt();assertTrue(f.current.isEmpty());assertEquals(0,V2FormFeedback.score(f.summary(),CoachMode.COACH))
        assertTrue(feed(f,10f,count=30).isEmpty());assertTrue(f.current.isEmpty())
        feed(f,count=1,anchor=true);feed(f,10f)
        assertEquals(100,V2FormFeedback.score(f.current,CoachMode.COACH))
        assertEquals(0,V2FormFeedback.score(f.summary(),CoachMode.COACH))
    }

    @Test fun betaExcludedAndMisleadingProxiesNeverGetScoreOrVoice() {
        val f=feedback(rule(status=RuleStatus.BETA),rule("knee_out_mean"),rule("palm_lat"),rule("stance_w"))
        assertFalse(f.supported);feed(f,count=1,anchor=true)
        assertTrue(feed(f,count=40).isEmpty());assertNull(V2FormFeedback.score(f.summary(),CoachMode.COACH))
    }

    @Test fun unjudgedItemsDoNotBecomeNormalAndDuplicateFeaturesDoNotDoubleScore() {
        val f=feedback(rule(),rule().copy(id="duplicate"),rule("head_pitch"))
        feed(f,count=1,anchor=true);feed(f,10f)
        assertEquals(2,f.summary().size)
        assertEquals(1,f.summary().count { it.overall==Verdict.OK })
        assertEquals(1,f.summary().count { it.overall==Verdict.ABSTAIN })
        assertEquals(100,V2FormFeedback.score(f.summary(),CoachMode.COACH))
    }

    @Test fun cooldownSurvivesInterruption() {
        val f=feedback(rule());feed(f,count=1,anchor=true);assertEquals(1,feed(f).size)
        f.interrupt();feed(f,count=1,anchor=true);assertTrue(feed(f).isEmpty())
    }
}
