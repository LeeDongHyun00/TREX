package com.example.trex_kotlin.routines

import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.TrexStore
import com.example.trex_kotlin.Workout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 루틴 코덱(공유·보관함 공통)과 보관함 규칙(docs/superpowers/specs/2026-10-29-routine-share-design.md §4). */
class RoutineCodecTest {

    private val squat = Workout("x1", "바벨 스쿼트", "3세트 x 12회", "5분", true, "하체", done = true, restSeconds = 60)

    private fun saved(id: String) = SavedRoutine(id, "루틴 $id", listOf(squat), null, 0L)

    @Test
    fun roundTripDropsDeviceFields() {
        val arr = encodeRoutineExercises(listOf(squat))
        assertFalse(arr.getJSONObject(0).has("id"))
        assertFalse(arr.getJSONObject(0).has("done"))
        val back = decodeRoutineExercises(arr, "r") { true }.single()
        assertEquals("기본 스쿼트", back.name)
        assertEquals("r-0", back.id)
        assertFalse(back.done)
        assertEquals(60, back.restSeconds)
        assertEquals(true, back.posture)
    }

    @Test
    fun unknownExerciseKeptWithoutPosture() {
        val arr = JSONArray().put(JSONObject().put("name", "처음 보는 운동").put("reps", "3세트 x 10회").put("duration", "4분")
            .put("posture", true).put("category", "하체"))
        val back = decodeRoutineExercises(arr, "r") { false }.single()
        assertEquals("처음 보는 운동", back.name)
        assertFalse(back.posture)
    }

    @Test
    fun brokenEntriesAreSkipped() {
        val arr = JSONArray().put(JSONObject().put("reps", "3세트")).put(encodeRoutineExercises(listOf(squat)).getJSONObject(0))
        assertEquals(1, decodeRoutineExercises(arr, "r") { true }.size)
    }

    @Test
    fun libraryAddsNewestFirstAndStopsAt50() {
        val full = (1..ROUTINE_LIBRARY_MAX).map { saved("$it") }
        assertNull(full.withAdded(saved("new")))
        assertEquals(listOf("b", "a"), emptyList<SavedRoutine>().withAdded(saved("a"))!!.withAdded(saved("b"))!!.map { it.id })
        assertEquals(listOf("b"), listOf(saved("a"), saved("b")).without("a").map { it.id })
    }

    @Test
    fun applyMakesFreshIds() {
        val plan = saved("a").toTodayPlan(1000L)
        assertEquals("shared-1000-0", plan.single().id)
        assertFalse(plan.single().done)
    }

    @Test
    fun storeRoundTrip() {
        val store = TrexStore(MemoryStore())
        store.saveRoutines(listOf(SavedRoutine("a", "하체 불태우기", listOf(squat, squat), "민수", 7L)))
        val back = store.loadRoutines().single()
        assertEquals("하체 불태우기", back.name)
        assertEquals(2, back.workouts.size)
        assertEquals("민수", back.from)
        assertEquals(7L, back.savedAtMillis)
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
