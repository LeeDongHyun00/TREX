package com.example.trex_kotlin.food

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 모델 입력 배치(§108d `FoodInputs`)를 고정한다 — Android `FoodDetector` 의 그리기와 iPhone `IosFoodModels` 가 같은 이 식을 쓴다.
 * letterbox 의 정수 자리·홀수 여백 버림·1픽셀 하한, 2단계 자리의 버림 좌표·가운데 정사각, 특징값 자리의 둘레 10 %·사진 안 자르기·224 가운데.
 */
class FoodInputsTest {

    @Test
    fun letterboxKeepsAspectOnWholePixelsInTheMiddle() {
        val landscape = FoodInputs.letterbox(1280, 960, 640, 640)
        assertEquals(PixelBox(0f, 0f, 1280f, 960f), landscape.src)
        assertEquals(PixelBox(0f, 80f, 640f, 560f), landscape.dst)
        // 세로 사진 — 줄인 폭(479.5 → 479)과 남는 칸(161 → 80)은 정수로 버린다
        assertEquals(PixelBox(80f, 0f, 559f, 640f), FoodInputs.letterbox(959, 1280, 640, 640).dst)
        // 아주 가는 그림도 한 픽셀은 남긴다
        assertEquals(PixelBox(319f, 0f, 320f, 640f), FoodInputs.letterbox(1, 5000, 640, 640).dst)
    }

    @Test
    fun cropFloorsSourceAndCentersPlacementInTheSquare() {
        val box = PixelBox(100.7f, 50.2f, 300.9f, 150.5f)
        val square = FoodInputs.crop(box, 640, 640)
        assertEquals(PixelBox(100f, 50f, 300f, 150f), square.src)
        assertEquals(FoodRegions.cropPlacement(box, 640), square.dst)
        // 정사각이 아닌 입력은 짧은 변 정사각을 가운데에 둔다
        val d = FoodRegions.cropPlacement(box, 640)
        assertEquals(PixelBox(d.left + 80f, d.top, d.right + 80f, d.bottom), FoodInputs.crop(box, 800, 640).dst)
        // 1픽셀보다 작은 자리도 한 픽셀은 자른다
        assertEquals(PixelBox(10f, 10f, 11f, 11f), FoodInputs.crop(PixelBox(10.2f, 10.4f, 10.6f, 10.9f), 640, 640).src)
    }

    @Test
    fun embedPadsTenPercentStaysInsideAndCentersShortSide() {
        // 1000×800 사진의 가운데 절반 → 둘레 10 % → (200, 160)–(800, 640), 짧은 변 480 → 224, 폭 280 은 가운데(−28 ~ 252)
        val middle = FoodInputs.embed(PixelBox(0.25f, 0.25f, 0.75f, 0.75f), 1000, 800)
        assertEquals(PixelBox(200f, 160f, 800f, 640f), middle.src)
        assertEquals(-28f, middle.dst.left, 1e-3f)
        assertEquals(0f, middle.dst.top, 1e-3f)
        assertEquals(252f, middle.dst.right, 1e-3f)
        assertEquals(224f, middle.dst.bottom, 1e-3f)
        // 가장자리 자리의 여유는 사진 밖으로 나가지 않는다
        assertEquals(PixelBox(0f, 0f, 110f, 88f), FoodInputs.embed(PixelBox(0f, 0f, 0.1f, 0.1f), 1000, 800).src)
        assertEquals(PixelBox(890f, 712f, 1000f, 800f), FoodInputs.embed(PixelBox(0.9f, 0.9f, 1f, 1f), 1000, 800).src)
    }
}
