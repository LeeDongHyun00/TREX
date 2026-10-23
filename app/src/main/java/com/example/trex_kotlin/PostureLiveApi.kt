package com.example.trex_kotlin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.trex_kotlin.posture.AnalyzerStats
import com.example.trex_kotlin.posture.LensInfo
import com.example.trex_kotlin.posture.ModeStore
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.SetLogStore
import com.example.trex_kotlin.posture.Vec3
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/*
 * 자세 화면의 플랫폼 경계(§108c). 분석 루프·판정 격자·코칭·세트 마감은 공유 `PostureLiveSessionScreen` 하나이고,
 * 플랫폼은 영상 → PoseSample(카메라·추론기·중력·열), 짧은 확인음, 카메라 권한, 앱 자원(자산·저장소)만 맡는다.
 * Android 구현은 `PostureCameraAndroid.kt`, iPhone 구현은 ios-ui 의 같은 이름 함수(`rememberPoseCamera` 등)다.
 */

/** 짧은 확인음(§7) — Android `ToneGenerator` / iPhone 시스템 소리. 종류 값은 Android `ToneGenerator.TONE_PROP_*` 그대로다(PostureLiveApiTest). */
interface RepTone {
    fun startTone(toneType: Int, durationMs: Int): Boolean
    fun release()

    companion object {
        const val TONE_PROP_BEEP = 24
        const val TONE_PROP_ACK = 25
        const val TONE_PROP_NACK = 26
        const val TONE_PROP_BEEP2 = 28
    }
}

/** 카메라 한 프레임 — 분석 스레드가 쥐고, [PoseCamera.analyze] 뒤 반드시 [close] 한다(영상 버퍼를 돌려준다). */
interface PoseFrame {
    fun close()
}

/**
 * 자세 화면의 카메라·추론기 — Android CameraX + MediaPipe / iPhone AVFoundation + MediaPipe iOS.
 * 추론 이후 처리는 두 플랫폼 모두 공유 `PoseLandmarks.sample` 이다(같은 피처).
 */
interface PoseCamera {
    /** 화면 회전(Surface.ROTATION_* 값) — 프리뷰·분석 영상의 방향. 바뀔 때 화면이 부른다. */
    fun setDisplayRotation(rotation: Int)
    /** 바닥 세 종목의 얼굴 메시(§101d) — 분석 스레드가 [analyze] 전에 정한다. */
    var faceEnabled: Boolean
    /**
     * [front] 카메라를 묶고, 취소될 때까지 프레임마다 [onFrame] 을 **분석 스레드**에서 부른다 — 한 번에 하나, 처리 중에 온 프레임은 버린다(최신만).
     * 묶지 못하면 예외를 던진다(화면이 오류 문구와 직접 기록을 보인다). [label] 은 진단 로그용.
     */
    suspend fun stream(front: Boolean, label: String, onFrame: (PoseFrame) -> Unit)
    /** 분석 스레드 — 추론해 PoseSample 을 만든다. [up] 이 IMU 값이 아니면 SCREEN_UP 그 객체. */
    fun analyze(frame: PoseFrame, now: Long, up: Vec3): PoseSample
    /** IMU 중력 → 월드 up([rotation] = 화면 회전, [front] = 전면 카메라). 센서가 없으면 SCREEN_UP 그 객체. */
    fun gravityUp(rotation: Int, front: Boolean): Vec3
    fun stats(): AnalyzerStats
    /** 바인딩된 렌즈 사양(§91 배치 지문) — 모르면 null. */
    val lens: LensInfo?
    /** OS 열 상태를 받을 수 있는가(세트 로그의 thermal 블록 — 못 받으면 null 로 남긴다). */
    val thermalSupported: Boolean
    /** 화면에 들어올 때 — 중력·열 센서를 켠다. [onThermal] 은 InferencePolicy.THERMAL_* 값, 메인 스레드. */
    fun start(onThermal: (Int) -> Unit)
    /** 화면을 떠날 때 — 센서를 끄고 추론기를 닫는다(진행 중 추론 뒤에, 분석 스레드에서). */
    fun stop()
    /** 영상 미리보기 — FIT_CENTER(카메라가 보는 전체, 분석 범위와 같다). */
    @Composable
    fun Preview(modifier: Modifier)
}

/** 자세 화면이 쓰는 앱 자원 — Android 의 `Context` 자리(자산·프로필·모드·세트 로그·수행자 ID·빌드 이름). */
interface PostureHost {
    /** 번들 자산 텍스트(UTF-8) — `posture/rules_mp_v0.json` 같은 Android assets 경로. */
    fun readAsset(path: String): String
    /** 프로필 키(cm) — 배치 지문(§91)의 척도. 없으면 null. */
    fun profileHeightCm(): Float?
    fun modeStore(): ModeStore
    fun setLogStore(): SetLogStore
    /** 설치 단위 익명 수행자 ID(재보정의 그룹 키). */
    fun subjectId(): String
    /** 어떤 빌드가 센 세트인지(세트 로그 app_version). */
    fun appVersion(): String?
}

/** 카메라 권한 — [granted] 면 화면이 카메라를 연다. [asked] 는 한 번 물었는가(거절 화면을 그때만 보인다). */
class CameraPermission(granted: Boolean) {
    var granted by mutableStateOf(granted)
    var asked by mutableStateOf(granted)
}

/**
 * 락 구간 — 두 플랫폼 공통(atomicfu: JVM 은 종전과 같은 객체 모니터, iPhone 은 재진입 락). 블록이 정확히 한 번 돈다는 계약이 있어
 * 바깥 `val` 을 안에서 처음 정할 수 있다(세트 마감의 사본들 — stdlib `synchronized` 와 같은 쓰임).
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> locked(lock: SynchronizedObject, block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return kotlinx.atomicfu.locks.synchronized(lock, block)
}
