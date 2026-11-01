package com.example.trex_kotlin

import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.HttpTransport
import com.example.trex_kotlin.trainingload.LoadLedger
import com.example.trex_kotlin.trainingload.LoadSet

/**
 * 앱 상태([AppViewModel])가 쓰는 플랫폼 기능의 경계(§108). 상태 코드는 이 인터페이스만 보고 Android·iPhone 에서 같은 소스로 돈다.
 * Android 구현은 [AndroidAppPlatform] 이다.
 */
interface AppPlatform {
    /** 이름별 키-값 저장소(`trex_store`·`trex_auth`·`trex_sync`). */
    fun preferences(name: String): KeyValueStore

    /** 서버 API 의 HTTP 전송. */
    val http: HttpTransport

    /** 바이트 그대로의 HTTP 전송 — 커뮤니티 사진 올리기·받기. */
    val binary: BinaryTransport

    /** 서버 주소. 비어 있으면 계정 기능을 쓰지 않는다. */
    val apiBaseUrl: String

    /** 구글 로그인의 웹 클라이언트 ID. 비어 있으면 구글 로그인을 쓰지 않는다. */
    val googleWebClientId: String

    /**
     * Apple 로그인을 쓸 수 있는가 — iPhone 은 기본 로그인 창(Swift 다리), Android 는 Apple 웹 로그인(설계 §9, Services ID 가 설정된 빌드).
     * 거짓이면 Apple 버튼을 그리지 않는다.
     */
    val appleSignInAvailable: Boolean

    /** 설치된 앱의 버전 이름 — 스토어·업데이트 창이 말하는 그 버전(Android `versionName`, iPhone `CFBundleShortVersionString`). */
    val appVersion: String

    /** 빌드 번호(Android `versionCode`, iPhone `CFBundleVersion`) — 같은 버전 이름으로 다시 올린 빌드를 가른다. */
    val appBuild: String

    fun muscleLoadDatabase(): MuscleLoadDatabase

    /** 자가 라벨을 연구용 라벨 파일에 덧붙인다. 파일 IO 는 구현이 메인 스레드 밖에서 한다. */
    fun appendSetLabel(label: SetSelfLabel)
}

/** 설정 화면의 '버전' 표기 — "1.3.0 (1)"(버전 이름과 빌드 번호, §119). 빌드 번호를 모르면 버전 이름만. */
fun appVersionLabel(version: String, build: String): String = if (build.isBlank()) version else "$version ($build)"

/** 근육 부하 장부 저장소 — 세트 원본과 파생 부하를 한 번에 맞추고 남은 장부를 돌려준다. Android 는 SQLite([MuscleLoadStore]). */
interface MuscleLoadDatabase {
    fun sync(current: List<LoadSet>, legacy: List<LoadSet>, now: Long, corrections: Map<String, Double?> = emptyMap()): LoadLedger
}
