package com.example.trex_kotlin.posture

import java.io.File

/**
 * 렙 검증 모드 (spec §61 — 폰 검증 Gate A, `research/external_rep_replay/GATE_A_RUNBOOK.md`).
 *
 * 켜는 법은 화면 메뉴가 아니라 **앱 전용 외부 폴더의 표시 파일**이다 — 검증은 어차피 USB 로 폰을 연결해서 하고,
 * 일반 사용자가 실수로 켤 길이 없어야 한다(켜지면 숫자가 숨고 자동 진행·음성이 꺼진다).
 *     adb shell touch /sdcard/Android/data/com.example.trex_kotlin/files/rep_validation.on   # 켜기
 *     adb shell rm    /sdcard/Android/data/com.example.trex_kotlin/files/rep_validation.on   # 끄기
 * (`research/external_rep_replay/pull_phone.py validation on|off|status` 가 같은 일을 한다.)
 *
 * 켜져 있을 때만 달라지는 것:
 *  - 반복 세트는 목표에 닿아도 자동으로 넘어가지 않는다 — 세트는 ✓ 로만 끝난다(세트 뒤 헛카운트·마지막 반복까지 잰다).
 *  - 음성을 끈다 — 코칭·숫자 발화가 동작과 템포를 바꾼다(원칙 #6).
 *  - 화면의 자동 횟수 숫자를 숨긴다 — 집계자(사람)가 앱 숫자에 끌려가지 않게. 세는 것·로그·리포트는 그대로다.
 *  - 세트 로그에 `validation`·`image`, 프레임마다 정규화 좌표 `xy`·월드 좌표 `w`·`up` 을 더 남긴다(오프라인 재분석용).
 * 꺼져 있으면(기본) 앱 동작과 세트 로그가 바이트 그대로다.
 */
object RepValidation {
    const val FLAG_FILE = "rep_validation.on"

    /** 화면 표기 — 켜져 있음을 늘 보인다(조용히 켜져 있으면 안 된다). */
    const val BANNER = "검증 모드 · 횟수 숨김 · 자동 진행 꺼짐 · 음성 꺼짐"
    const val HIDDEN_COUNT = "검증 중"

    /** [dir] = `context.getExternalFilesDir(null)`. 폴더가 없거나 읽을 수 없으면 꺼짐. */
    fun isOn(dir: File?): Boolean = runCatching { dir != null && File(dir, FLAG_FILE).isFile }.getOrDefault(false)
}
