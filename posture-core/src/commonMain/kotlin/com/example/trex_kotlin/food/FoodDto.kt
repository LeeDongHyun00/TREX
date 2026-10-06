package com.example.trex_kotlin.food
/** 플랫폼 이미지 없이 공유하는 음식 이름/점수 입력. */
data class DetectedFood(val name: String, val confidence: Float, val photoIndex: Int)
