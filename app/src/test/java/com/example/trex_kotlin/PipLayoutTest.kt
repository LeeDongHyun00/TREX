package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class PipLayoutTest {
    @Test fun cameraNeverOverlapsMovingPanelAtAnyProgress() {
        for ((w,h) in listOf(320 to 700, 400 to 840, 840 to 400, 1200 to 900)) {
            val base = sessionRegions(w,h,null,1f)
            for (i in 0..100) {
                val p = i / 100f
                val r = pipCameraRegion(w,h,base,null,1f,p,160)
                val side = base.controls.left > 0
                val boundary = if (side) base.controls.left + ((1-p)*base.controls.width).toInt()
                    else base.controls.top + ((1-p)*base.controls.height).toInt()
                assertTrue(r.width>0 && r.height>0)
                assertTrue(r.left>=0 && r.top>=0 && r.right<=w && r.bottom<=h)
                assertTrue(if (side) r.right<=boundary else r.bottom<=boundary)
            }
            assertEquals(ContentRect(0,0,w,h),pipCameraRegion(w,h,base,null,1f,0f,160))
        }
    }
    @Test fun separatingHingeKeepsCameraInItsOwnPane() {
        val fold=ContentRect(0,360,840,380)
        val base=sessionRegions(840,900,fold,1f)
        for(p in listOf(0f,.5f,1f)) assertEquals(base.camera,pipCameraRegion(840,900,base,fold,1f,p,140))
    }
}
