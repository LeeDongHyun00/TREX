package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ActionAssessmentTest {
    private val labels=(0..25).map{"exercise$it"}+"__other_exercise__"
    private val calibration=ActionCalibration(labels,1f,.7f,-5f)
    @Test fun finiteConfidentPredictionStillCannotSpeakOrCount() {
        val z=FloatArray(27); z[0]=10f
        val a=calibration.assess(0,1,z,"exercise0",1f)
        assertEquals(ActionState.TARGET_CANDIDATE,a.state)
        assertFalse(a.canAffectCount); assertFalse(a.canSpeak)
        assertEquals(ActionState.OTHER_EXERCISE,calibration.assess(0,1,z,"exercise1",1f).state)
    }
    @Test fun everyStateIsShadowOnly() {
        ActionState.entries.forEach { state ->
            val a=ActionAssessment(0,0,state)
            assertFalse(a.canAffectCount); assertFalse(a.canSpeak)
        }
    }
    @Test fun novelAmbiguousAndRuntimeFailureAreSeparate() {
        assertEquals(ActionState.NOVEL,calibration.assess(0,0,FloatArray(27),"exercise0",0f).state)
        val c=ActionCalibration(labels,1f,.7f,0f)
        assertEquals(ActionState.AMBIGUOUS,c.assess(0,0,FloatArray(27),"exercise0",0f).state)
        assertEquals(ActionState.UNAVAILABLE,c.assess(0,0,FloatArray(27){Float.NaN},"exercise0",0f).state)
    }
    @Test fun jsonPreservesFailureAndMasksWithoutNaN() {
        val packet=PosePacketV2(0,123456789,1,640,480,FloatArray(66){Float.NaN},null,
            FloatArray(33),FloatArray(33),Vec3(0f,1f,0f),false,true)
        val log=ActionSessionLog("model","hash",listOf(ActionAssessment(0,1,ActionState.UNAVAILABLE,reason="bad\"input")),
            listOf(packet),0,0,0,true)
        val json=ActionLogJson.encode(log)
        assertFalse(json.contains("NaN")); assertTrue(json.contains("\"can_speak\":false"))
        assertTrue(json.contains("bad\\\"input"))
        File("build/reports/action-log-fixture.json").apply { parentFile.mkdirs(); writeText(json) }
    }
}
