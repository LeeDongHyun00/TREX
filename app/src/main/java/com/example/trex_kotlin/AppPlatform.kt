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

    fun muscleLoadDatabase(): MuscleLoadDatabase

    /** 자가 라벨을 연구용 라벨 파일에 덧붙인다. 파일 IO 는 구현이 메인 스레드 밖에서 한다. */
    fun appendSetLabel(label: SetSelfLabel)
}

/** 근육 부하 장부 저장소 — 세트 원본과 파생 부하를 한 번에 맞추고 남은 장부를 돌려준다. Android 는 SQLite([MuscleLoadStore]). */
interface MuscleLoadDatabase {
    fun sync(current: List<LoadSet>, legacy: List<LoadSet>, now: Long, corrections: Map<String, Double?> = emptyMap()): LoadLedger
}
