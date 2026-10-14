package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class PushupGeometryTest {
    private fun point(xy: FloatArray, id: Int, x: Float, y: Float) { xy[id*2]=x; xy[id*2+1]=y }
    private fun side(kneeling: Boolean = false, liftedFeet: Boolean = false): FloatArray {
        val xy=FloatArray(66) { .5f }
        point(xy,0,.19f,.43f)
        for (offset in 0..1) {
            val dy=offset*.02f
            point(xy,7+offset,.18f,.365f+dy)
            point(xy,11+offset,.25f,.4f+dy)
            point(xy,13+offset,.25f,.58f+dy)
            point(xy,15+offset,.25f,.75f+dy)
            point(xy,23+offset,.5f,.525f+dy)
            point(xy,25+offset,.68f,(if(kneeling) .735f else .615f)+dy)
            point(xy,27+offset,if(liftedFeet) .75f else .85f,(if(liftedFeet) .5f else if(kneeling) .65f else .7f)+dy)
            point(xy,31+offset,if(liftedFeet) .8f else .9f,(if(liftedFeet) .55f else .75f)+dy)
        }
        return xy
    }
    private fun front(handWidth: Float): FloatArray {
        val xy=FloatArray(66) { .5f }
        point(xy,11,.4f,.35f);point(xy,12,.6f,.35f)
        point(xy,23,.44f,.6f);point(xy,24,.56f,.6f)
        point(xy,25,.45f,.75f);point(xy,26,.55f,.75f)
        point(xy,27,.47f,.9f);point(xy,28,.53f,.9f)
        point(xy,15,.5f-.1f*handWidth,.55f);point(xy,16,.5f+.1f*handWidth,.55f)
        return xy
    }
    private fun visibility()=FloatArray(33) { if(it in setOf(8,12,14,16,24,26,28,32)) .6f else .95f }
    private fun features(xy: FloatArray = side(),vis: FloatArray = visibility())=PushupGeometry.features(xy,vis,1000,1000)
    private fun left(base: String)="${base}_L"
    private fun mirror(xy: FloatArray)=xy.copyOf().apply { for(i in 0..32) this[i*2]=1f-xy[i*2] }

    @Test fun straightLegAndSupportGeometryAreObservedWithoutAFormVerdict() {
        val f=features()
        assertEquals(1f,f.getValue(PushupGeometry.READY),0f)
        assertEquals(0f,f.getValue(PushupGeometry.SIDE),0f)
        assertTrue(f.getValue(left(PushupGeometry.KNEE))>170f)
        assertTrue(f.getValue(left(PushupGeometry.KNEE_CLEARANCE))>.3f)
        assertTrue(listOf(PushupGeometry.HIP,PushupGeometry.HEAD,PushupGeometry.NECK).all { f[left(it)]?.isFinite()==true })
        assertFalse(f.containsKey(PushupGeometry.HAND_WIDTH))
        assertFalse(f.keys.any { it.endsWith("_R") })
    }

    @Test fun bentKneesDoNotSuppressHeadHipOrKneeMeasurements() {
        val f=features(side(kneeling=true))
        assertEquals(1f,f.getValue(PushupGeometry.READY),0f)
        assertTrue(f.getValue(left(PushupGeometry.KNEE))<130f)
        assertTrue(f.getValue(left(PushupGeometry.KNEE_CLEARANCE))<.1f)
        assertTrue(listOf(PushupGeometry.HIP,PushupGeometry.HEAD,PushupGeometry.NECK).all { f[left(it)]?.isFinite()==true })
    }

    @Test fun liftedFeetKeepNegativeClearanceInsteadOfAnAbsoluteDistance() {
        val f=features(side(kneeling=true,liftedFeet=true))
        assertTrue(f.getValue(left(PushupGeometry.KNEE_CLEARANCE))<0f)
    }

    @Test fun missingFootOrWristLeavesKneeAngleButNoSupportClearance() {
        for(id in listOf(15,31)) {
            val f=features(vis=visibility().apply { this[id]=.1f })
            assertTrue(f.containsKey(left(PushupGeometry.KNEE)))
            assertFalse(f.containsKey(left(PushupGeometry.KNEE_CLEARANCE)))
        }
    }

    @Test fun outOfFrameAndNonfiniteFootCoordinatesCannotProveSupport() {
        for(value in listOf(-.1f,1.1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            val xy=side().apply { this[62]=value }
            val f=features(xy)
            assertTrue(f.containsKey(left(PushupGeometry.KNEE)))
            assertFalse(f.containsKey(left(PushupGeometry.KNEE_CLEARANCE)))
        }
        assertFalse(features(vis=visibility().apply { this[31]=Float.NaN }).containsKey(left(PushupGeometry.KNEE_CLEARANCE)))
    }

    @Test fun occludedLegsAreUnobservedRatherThanStraightLegs() {
        val f=features(vis=visibility().apply { this[25]=0f;this[26]=0f })
        assertFalse(f.containsKey(PushupGeometry.READY))
        assertFalse(f.keys.any { it.startsWith(PushupGeometry.KNEE) })
    }

    @Test fun hiddenFaceDoesNotDiscardLegSupportGeometry() {
        val f=features(vis=visibility().apply { this[0]=0f;this[7]=0f;this[8]=0f })
        assertTrue(f.containsKey(left(PushupGeometry.KNEE_CLEARANCE)))
        assertFalse(f.containsKey(left(PushupGeometry.HEAD)))
        assertFalse(f.containsKey(left(PushupGeometry.NECK)))
    }

    @Test fun mirrorPreservesAllScalarMeasurementsIncludingSignedClearance() {
        for(xy in listOf(side(),side(kneeling=true),side(kneeling=true,liftedFeet=true),front(1.4f))) {
            val a=features(xy);val b=features(mirror(xy))
            assertEquals(a.keys,b.keys)
            for((key,value) in a) assertEquals(key,value,b.getValue(key),.001f)
        }
    }

    @Test fun handWidthIsProjectedAlongVisibleShouldersForNarrowAndWideHands() {
        for(ratio in listOf(.5f,1f,1.5f,2f)) {
            val f=features(front(ratio))
            assertEquals(ratio,f.getValue(PushupGeometry.HAND_WIDTH),.001f)
            assertFalse(f.containsKey(PushupGeometry.READY))
        }
    }

    @Test fun invisibleWristsOrCollapsedShoulderWidthCannotBecomeNormalHandWidth() {
        assertFalse(features(front(1f),visibility().apply { this[16]=.1f }).containsKey(PushupGeometry.HAND_WIDTH))
        assertFalse(features(front(1f).apply { this[24]=this[22]+.005f }).containsKey(PushupGeometry.HAND_WIDTH))
        assertFalse(features(front(1f).apply { this[30]=Float.NaN }).containsKey(PushupGeometry.HAND_WIDTH))
        assertFalse(features(front(1f).apply { this[32]=1.1f }).containsKey(PushupGeometry.HAND_WIDTH))
        assertEquals(.02f,features(front(.02f)).getValue(PushupGeometry.HAND_WIDTH),.001f)
    }

    @Test fun handProjectionIgnoresDepthAxisOffsetsAndImageRoll() {
        val xy=front(1.5f).apply { this[31]=.45f;this[33]=.65f }
        val radians=.3
        val rotated=xy.copyOf().apply {
            for(i in 0..32) {
                val x=xy[2*i]-.5;val y=xy[2*i+1]-.5
                this[2*i]=(.5+x*kotlin.math.cos(radians)-y*kotlin.math.sin(radians)).toFloat()
                this[2*i+1]=(.5+x*kotlin.math.sin(radians)+y*kotlin.math.cos(radians)).toFloat()
            }
        }
        assertEquals(1.5f,features(xy).getValue(PushupGeometry.HAND_WIDTH),.001f)
        assertEquals(1.5f,features(rotated).getValue(PushupGeometry.HAND_WIDTH),.001f)
    }

    @Test fun imageAspectRatioUsesPixelGeometryAndInvalidInputsReturnNothing() {
        val a=features()
        val stretched=side().copyOf().apply { for(i in 0..32) this[i*2]*=.5f }
        val b=PushupGeometry.features(stretched,visibility(),2000,1000)
        assertEquals(a.keys,b.keys)
        for((key,value) in a) assertEquals(key,value,b.getValue(key),.001f)
        assertTrue(PushupGeometry.features(FloatArray(65),visibility(),1000,1000).isEmpty())
        assertTrue(PushupGeometry.features(side(),FloatArray(32),1000,1000).isEmpty())
        assertTrue(PushupGeometry.features(side(),visibility(),0,1000).isEmpty())
    }

    @Test fun extremelyNarrowHandsReachTheRepExclusionInsteadOfAbstaining() {
        for(width in listOf(0f,.02f,.08f,.4f)) {
            val f=features(front(width))
            assertEquals(width,f.getValue(PushupGeometry.HAND_WIDTH),.001f)
            val ev=RepFormEvaluator(PushupFormSpecs.checks(),"wrist_shoulder_d",.3f)
            val wave=listOf(1.4f,1.4f,1.4f,1.4f,1.2f,1f,.8f,.6f,.6f,.8f,1f,1.2f,1.4f,1.4f,1.4f)
            wave.forEachIndexed{i,v->ev.onFrame(i*300L,f+("wrist_shoulder_d" to v))}
            val rep=ev.onCycle(4200L,.6f,1.4f,900L)
            assertTrue("$width",RequestedFormCoaching.excluded(rep))
        }
    }
}
