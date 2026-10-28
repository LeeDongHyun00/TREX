package com.example.trex_kotlin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.PixelBox

/*
 * 사진 식단의 플랫폼 경계(§108d). 흐름·판정·화면은 공유 `PhotoFoodSheet` 와 `food/FoodPipeline` 하나이고,
 * 플랫폼은 사진(픽셀·화면 그림), 갤러리·카메라, 모델 파일·TFLite 추론만 맡는다.
 * Android 구현은 `PhotoFoodAndroid.kt`, iPhone 구현은 ios-ui 의 같은 이름 함수다.
 */

/** 사진 한 장 — Android Bitmap / iPhone UIImage. 사진은 기기 밖으로 나가지 않고 디스크에도 쓰지 않는다. */
interface FoodPhoto {
    val width: Int
    val height: Int
    /** 화면에 그릴 그림. */
    val image: ImageBitmap
    /** 최대 변이 [maxDimension] 을 넘으면 비율을 유지해 줄인 사본, 이미 작으면 자신. */
    fun scaledToMax(maxDimension: Int): FoodPhoto
    /** 픽셀 영역을 잘라낸 사본. */
    fun crop(left: Int, top: Int, width: Int, height: Int): FoodPhoto
    /**
     * 커뮤니티에 올릴 JPEG — 긴 변 [maxDimension] 으로 줄여([com.example.trex_kotlin.community.UploadSizing]) 픽셀에서 새로 만든다.
     * 원본 파일을 보내지 않으니 촬영 위치 같은 EXIF 가 따라가지 않는다. 사용자가 글에 직접 고른 사진에만 쓴다.
     */
    fun jpeg(maxDimension: Int = 1080, quality: Int = 85): ByteArray
}

/** 갤러리에서 고른 사진 한 장 — 분석 스레드에서 [load] 로 디코딩한다(추론용 최대 변 1280, 방향 반영). 실패하면 null. */
fun interface FoodPhotoLoader {
    fun load(): FoodPhoto?
}

/** 기억할 자리 하나(내 음식 기억) — 이름·사진·사진 대비 0~1 자리·모델이 붙였던 이름(사용자가 고쳤을 때). */
class FoodMemorySpot(val name: String, val photo: FoodPhoto, val box: PixelBox, val correctedFrom: String?)

/** 음식 인식기 — 모델·기억은 앱 수명이다. [warmUp]·[detect] 는 블로킹이라 백그라운드에서 부른다. */
interface FoodRecognizer {
    /** 시트를 열 때 미리 불러, 첫 분석이 모델 로딩 지연까지 떠안지 않게 한다. */
    fun warmUp()
    fun detect(photos: List<FoodPhoto>): FoodDetectionResult
    /** 사용자가 고친 자리를 기억한다 — 특징값 계산·저장은 화면 수명과 무관하게 백그라운드에서 끝난다. */
    fun remember(spots: List<FoodMemorySpot>)
}

/** 시트 안의 카메라(후면, 사진 한 장). 권한이 있을 때만 만든다. */
interface FoodCamera {
    /** 카메라를 열지 못했을 때의 안내(촬영 불가) — Compose 상태. */
    val error: String?
    /** 미리보기(화면을 채운다). 이것이 화면에 있는 동안 카메라가 묶여 있다. */
    @Composable
    fun Preview(modifier: Modifier)
    /** 한 장 찍는다(추론용 최대 변 1280, 방향 반영) — 성공은 [onPhoto], 실패는 [onError], 둘 다 메인 스레드. */
    fun capture(onPhoto: (FoodPhoto) -> Unit, onError: () -> Unit)
}
