package com.example.trex_kotlin

/**
 * 키-값 저장소의 경계(§108). Android SharedPreferences 와 같은 이름·같은 의미로 만들어 저장소 코드([TrexStore]·동기화)가
 * iPhone 에서도 그대로 돈다. Android 구현은 SharedPreferences 를 그대로 감싸고([SharedPreferencesStore]), iPhone 은 NSUserDefaults 다.
 */
interface KeyValueStore {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getLong(key: String, defValue: Long): Long
    fun contains(key: String): Boolean
    fun edit(): Editor

    /**
     * 항목이 바뀔 때마다 그 키로 [onChange] 를 부른다(메인 스레드). 다시 부르면 앞의 것을 바꾼다.
     * SharedPreferences 는 리스너를 약하게 잡지만 이 경계는 구현이 붙들어 둔다 — 부르는 쪽이 따로 잡아 둘 필요가 없다.
     */
    fun setChangeListener(onChange: (String) -> Unit)

    interface Editor {
        /** [value] 가 null 이면 지운다(SharedPreferences 와 같다). */
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** 메모리에 바로 반영하고 디스크에는 나중에 쓴다. */
        fun apply()
        /** 디스크까지 바로 쓴다. */
        fun commit(): Boolean
    }
}
