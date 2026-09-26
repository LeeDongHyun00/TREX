package com.example.trex_kotlin.posture

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Android의 실제 JSON 로더와 배포 자산으로 누락/등급/실시간 연결을 확인한다. 촬영 정확도 검사는 아니다. */
class PlankAssetTest {
    @Test(timeout=10000) fun packagedRulesDetectHeadLiftAndBadStart() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val set=PostureRuleSet.load(context,FLOOR_RULES_ASSET)
        assertEquals("floor_v0.4",set.version)
        val rules=set.rulesFor("플랭크")
        assertEquals(3,rules.size)
        assertTrue(rules.all { it.kind=="alignment" && it.status==RuleStatus.BETA && it.alignmentConfig!=null })
        val tracker=PlankAlignmentTracker(rules)
        val input=mapOf(PlankGeometry.READY to 1f,PlankGeometry.SIDE to 0f,
            PlankGeometry.HIP to -.1f,PlankGeometry.HEAD to 60f,PlankGeometry.NECK to 0f)
        for(t in 0L..1500L step 250)tracker.add(t,input)
        assertEquals(Verdict.VIOLATION,tracker.snapshot.items.first{it.rule.baseFeature==PlankGeometry.HIP}.verdict)
        assertEquals(Verdict.VIOLATION,tracker.snapshot.items.first{it.rule.baseFeature==PlankGeometry.HEAD}.verdict)
        assertFalse(tracker.snapshot.referenceEligible)
    }

    @Test(timeout=10000) fun independentStandingShipRuleIsStillLoadedWithBeta() {
        val set=PostureRuleSet.load(InstrumentationRegistry.getInstrumentation().targetContext)
        val rules=set.rulesFor("스탠딩 사이드 크런치")
        assertTrue(rules.any { it.condition=="척추의 중립" && it.status==RuleStatus.SHIP && it.subtype=="all" })
    }
}
