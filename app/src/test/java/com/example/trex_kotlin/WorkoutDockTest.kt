package com.example.trex_kotlin

import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §139 — 운동 탭 조작부 자리 바꾸기(배치·움직임 규칙)와 목표 요일(운동 요일 ∪ 주간 루틴 요일)·옛 저장본 옮기기. */
class WorkoutDockTest {

    @Test fun placementSwapsOnlyInWorkoutRemote() {
        // 평소 리모컨: 운동 기록·운동 시작이 내비, 셋은 머리줄
        val normal = dockPlacement(swapped = false, workoutRemote = true)
        assertEquals(DockHost.Nav, normal[DockItem.Record]); assertEquals(DockHost.Nav, normal[DockItem.Start])
        listOf(DockItem.Routines, DockItem.Library, DockItem.Share).forEach { assertEquals(DockHost.Header, normal[it]) }
        // 교체: 서로 자리를 바꾼다
        val swapped = dockPlacement(swapped = true, workoutRemote = true)
        assertEquals(DockHost.Header, swapped[DockItem.Record]); assertEquals(DockHost.Header, swapped[DockItem.Start])
        listOf(DockItem.Routines, DockItem.Library, DockItem.Share).forEach { assertEquals(DockHost.Nav, swapped[it]) }
        // 리모컨이 접히면(탭 다섯 개) 교체 상태여도 셋은 머리줄로 돌아오고 운동 기록·시작은 어디에도 없다(예전과 같다)
        for (s in listOf(false, true)) {
            val folded = dockPlacement(swapped = s, workoutRemote = false)
            assertEquals(null, folded[DockItem.Record]); assertEquals(null, folded[DockItem.Start])
            listOf(DockItem.Routines, DockItem.Library, DockItem.Share).forEach { assertEquals(DockHost.Header, folded[it]) }
        }
    }

    @Test fun motionFliesBetweenHostsAndLeavesNavMorphAlone() {
        assertEquals(DockMotion.Fly, dockMotion(DockHost.Nav, DockHost.Header))
        assertEquals(DockMotion.Fly, dockMotion(DockHost.Header, DockHost.Nav))
        assertEquals(DockMotion.Vanish, dockMotion(DockHost.Header, null))
        assertEquals(DockMotion.Appear, dockMotion(null, DockHost.Header))
        // 내비 ↔ 없음은 MorphNav 의 제자리 모핑이 맡는다 — 유령을 띄우면 접히는 알약 위에 겹친다
        assertEquals(DockMotion.None, dockMotion(DockHost.Nav, null))
        assertEquals(DockMotion.None, dockMotion(null, DockHost.Nav))
        assertEquals(DockMotion.None, dockMotion(DockHost.Header, DockHost.Header))
    }

    @Test fun everySwapMovesAllFiveButtons() {
        val before = dockPlacement(swapped = false, workoutRemote = true)
        val after = dockPlacement(swapped = true, workoutRemote = true)
        assertTrue(DockItem.entries.all { dockMotion(before[it], after[it]) == DockMotion.Fly })
        // 교체 상태에서 리모컨을 접으면 셋은 날아 올라가고, 운동 기록·시작은 머리줄에서 사라진다
        val folded = dockPlacement(swapped = true, workoutRemote = false)
        assertEquals(DockMotion.Vanish, dockMotion(after[DockItem.Start], folded[DockItem.Start]))
        assertEquals(DockMotion.Fly, dockMotion(after[DockItem.Library], folded[DockItem.Library]))
    }

    @Test fun targetDaysAreWorkoutDaysOrRoutineDays() {
        val monWedFri = dayBit(DayOfWeek.MONDAY) or dayBit(DayOfWeek.WEDNESDAY) or dayBit(DayOfWeek.FRIDAY)
        assertEquals(0b0010101, monWedFri)
        val weekly = WeeklyRoutine().assigned(DayOfWeek.TUESDAY, "preset:R01")
        assertTrue(isWorkoutTargetDay(DayOfWeek.MONDAY, monWedFri, weekly))      // 운동 요일
        assertTrue(isWorkoutTargetDay(DayOfWeek.TUESDAY, monWedFri, weekly))     // 루틴 요일
        assertFalse(isWorkoutTargetDay(DayOfWeek.THURSDAY, monWedFri, weekly))   // 둘 다 아님 = 계획된 휴식
        assertEquals(0b0010100, withDay(monWedFri, DayOfWeek.MONDAY, on = false))
        assertEquals(monWedFri, withDay(monWedFri, DayOfWeek.MONDAY, on = true))
    }

    @Test fun oldProfilesKeepTheirTargetDaysAfterMigration() {
        val onboarding = 0b0010101   // 월·수·금
        // 루틴이 없던 사람: 온보딩 요일 그대로
        assertEquals(onboarding, migratedDayMask(onboarding, WeeklyRoutine()))
        // 루틴이 있던 사람: §131 에서는 루틴 요일만 목표였다 — 그 요일로 맞춰 새 규칙에서도 같다(연속이 갑자기 끊기지 않게)
        val weekly = WeeklyRoutine().assigned(DayOfWeek.TUESDAY, "preset:R01").assigned(DayOfWeek.THURSDAY, "saved:x")
        val migrated = migratedDayMask(onboarding, weekly)
        assertEquals(dayBit(DayOfWeek.TUESDAY) or dayBit(DayOfWeek.THURSDAY), migrated)
        DayOfWeek.entries.forEach { d -> assertEquals(weekly.isTarget(d), isWorkoutTargetDay(d, migrated, weekly)) }
    }

    @Test fun turningOffARoutineDayDropsThatRoutine() {
        val weekly = WeeklyRoutine().assigned(DayOfWeek.MONDAY, "preset:R01").assigned(DayOfWeek.THURSDAY, "saved:x")
        val keepMonday = dayBit(DayOfWeek.MONDAY) or dayBit(DayOfWeek.SATURDAY)
        assertEquals(listOf(DayOfWeek.THURSDAY), routineDaysTurnedOff(keepMonday, weekly))
        assertEquals(emptyList<DayOfWeek>(), routineDaysTurnedOff(ALL_DAYS_MASK, weekly))
    }

    @Test fun profileStoresTheTargetDayVersionAndOldJsonReadsAsV1() {
        val kv = MemoryStore()
        val store = TrexStore(kv)
        store.saveProfile(UserProfile(dayMask = 0b0000011))
        val back = store.loadProfile()!!
        assertEquals(0b0000011, back.dayMask)
        assertEquals(TARGET_DAYS_VERSION, back.daysVersion)
        // §139 이전 앱이 쓴 프로필(판 표시 없음) — §131 규칙으로 읽혀 앱이 한 번 옮긴다
        kv.edit().putString("profile", """{"goal":"general","dayMask":21}""").apply()
        assertEquals(1, store.loadProfile()!!.daysVersion)
        // 교체 상태는 기기 설정 — 기본은 꺼짐
        assertFalse(store.workoutDockSwapped)
        store.workoutDockSwapped = true
        assertTrue(TrexStore(kv).workoutDockSwapped)
    }

    private class MemoryStore : KeyValueStore {
        val values = HashMap<String, Any?>()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun contains(key: String): Boolean = key in values
        override fun setChangeListener(onChange: (String) -> Unit) {}
        override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) = apply { if (value == null) values.remove(key) else values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { if (values == null) this@MemoryStore.values.remove(key) else this@MemoryStore.values[key] = values }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }
}
