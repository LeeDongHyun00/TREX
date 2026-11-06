package com.example.trex_kotlin.posture

import com.example.trex_kotlin.KeyValueStore

/**
 * 세션 모드(spec §29)의 종목별 저장 — 키 `mode_<종목>` = "track" | "coach"(없으면 COACH). 두 플랫폼이 같이 쓴다(§108c):
 * Android 는 SharedPreferences "trex_posture"(`ModeStore(context)`), iPhone 은 같은 이름의 UserDefaults 묶음.
 */
class ModeStore(private val prefs: KeyValueStore) {
    fun get(exercise: String): CoachMode =
        if (prefs.getString("mode_$exercise", null) == "track") CoachMode.TRACK else CoachMode.COACH

    fun set(exercise: String, mode: CoachMode) {
        prefs.edit().putString("mode_$exercise", if (mode == CoachMode.TRACK) "track" else "coach").apply()
    }
}
