package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 별도 규칙의 존재가 독립 평가 항목을 없애면 안 된다. */
class PostureRulesSelectionTest {
    private val general = PostureRule("all","종목","척추의 중립","all",RuleStatus.SHIP,null,
        "shoulder_h_R__mean","shoulder_h_R","mean","world",">",10f,"C","정면",.93f,.9f,100,true,emptyList())
    private fun selected(vararg rules: PostureRule) = PostureRuleSet("test","",rules.toList()).rulesFor("종목")

    @Test fun aDifferentFeatureDoesNotHideTheGeneralRule() {
        val specific=general.copy(id="lean",subtype="forward_lean",feature="torso_incl__mean",baseFeature="torso_incl",status=RuleStatus.BETA)
        assertEquals(setOf("all","lean"),selected(general,specific).map{it.id}.toSet())
    }
    @Test fun aDifferentConditionDoesNotHideTheGeneralRule() {
        val specific=general.copy(id="arm",subtype="arm",condition="팔 위치")
        assertEquals(2,selected(general,specific).size)
    }
    @Test fun betaDoesNotReplaceShipEvenForTheSameFeature() {
        val specific=general.copy(id="beta",subtype="lean",status=RuleStatus.BETA)
        assertEquals(2,selected(general,specific).size)
    }
    @Test fun theSameConditionFeatureKindAndGradeUsesTheSpecificRule() {
        val specific=general.copy(id="specific",subtype="lean")
        assertEquals(listOf("specific"),selected(general,specific).map{it.id})
    }
}
