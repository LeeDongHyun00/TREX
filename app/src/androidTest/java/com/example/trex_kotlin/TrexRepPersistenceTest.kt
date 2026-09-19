package com.example.trex_kotlin

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.RepMovementPattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Android의 실제 org.json/SharedPreferences를 쓰되 사용자 저장소와 분리한다. */
@RunWith(AndroidJUnit4::class)
class TrexRepPersistenceTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: TrexStore

    @Before fun createIsolatedStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rep_persistence_test_${UUID.randomUUID()}"
        prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("demo_cleanup_v2", true).commit()
        store = TrexStore(context, name)
    }

    @After fun clearIsolatedStore() { prefs.edit().clear().commit() }

    private fun workout(id: String, pattern: RepMovementPattern? = null) = Workout(
        id = id, name = "덤벨 컬", reps = "12회", duration = "1분", posture = true,
        category = "상체", repMovementPattern = pattern,
    )

    @Test fun planRoundTripsEveryPatternAndMissingDefault() {
        val plan = RepMovementPattern.entries.map { workout(it.name, it) } + workout("default")
        store.savePlan(plan)
        val loaded = store.loadPlan()!!
        assertEquals(plan.map { it.id }, loaded.map { it.id })
        assertEquals(plan.map { it.repMovementPattern }, loaded.map { it.repMovementPattern })
        val json = JSONArray(prefs.getString("plan", null))
        assertFalse(json.getJSONObject(json.length() - 1).has("repMovementPattern"))
    }

    @Test fun unknownAndJsonNullPatternsPreserveTheWholePlan() {
        store.savePlan(listOf(workout("future"), workout("json-null"), workout("legacy")))
        val json = JSONArray(prefs.getString("plan", null))
        json.getJSONObject(0).put("repMovementPattern", "FUTURE_PATTERN")
        json.getJSONObject(1).put("repMovementPattern", JSONObject.NULL)
        prefs.edit().putString("plan", json.toString()).commit()
        val loaded = store.loadPlan()
        assertNotNull(loaded)
        assertEquals(listOf("future", "json-null", "legacy"), loaded!!.map { it.id })
        loaded.forEach { assertNull(it.repMovementPattern) }
    }

    private fun saveCorrection(correction: PostureCorrection) {
        store.saveHistory(listOf(WorkoutHistoryDay(
            epochDay = LocalDate.now().toEpochDay(), dayLabel = "오늘", dateLabel = "테스트",
            items = listOf(WorkoutHistoryItem("덤벨 컬", "12회", 1, 3, correction)),
            averageMinutes = 1, averageCalories = 3,
        )))
    }

    private fun loadedCorrection() = store.loadHistory()!!.single().items.single().postureCorrection!!

    @Test fun observedCountsRoundTripIndependentlyOfManualActualReps() {
        val correction = PostureCorrection(
            focus = "판정한 항목 범위 내", kind = "clean", actualReps = 20,
            observedLeftReps = 2, observedRightReps = 3, observedBothReps = 0,
            observedUnknownReps = 1, repMovementPattern = RepMovementPattern.ALTERNATING_EACH.name,
        )
        saveCorrection(correction)
        assertEquals(correction, loadedCorrection())
        saveCorrection(loadedCorrection().copy(actualReps = 7))
        val edited = loadedCorrection()
        assertEquals(7, edited.actualReps)
        assertEquals(2, edited.observedLeftReps)
        assertEquals(3, edited.observedRightReps)
        assertEquals(0, edited.observedBothReps)
        assertEquals(1, edited.observedUnknownReps)
    }

    @Test fun legacyRecordDoesNotInventZeroObservations() {
        saveCorrection(PostureCorrection(focus = "판정한 항목 범위 내", kind = "clean", actualReps = 12))
        val item = JSONArray(prefs.getString("history", null)).getJSONObject(0).getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("postureObservedLeftReps"))
        val loaded = loadedCorrection()
        assertEquals(12, loaded.actualReps)
        assertNull(loaded.observedLeftReps)
        assertNull(loaded.observedRightReps)
        assertNull(loaded.observedBothReps)
        assertNull(loaded.observedUnknownReps)
        assertNull(loaded.repMovementPattern)
    }

    @Test fun nullAndNegativeObservationsStayMissingWhileZeroIsObserved() {
        saveCorrection(PostureCorrection(focus = "관측 기록", kind = "reference"))
        val json = JSONArray(prefs.getString("history", null))
        val item = json.getJSONObject(0).getJSONArray("items").getJSONObject(0)
        item.put("postureObservedLeftReps", JSONObject.NULL)
        item.put("postureObservedRightReps", -1)
        item.put("postureObservedBothReps", 0)
        item.put("postureRepMovementPattern", JSONObject.NULL)
        prefs.edit().putString("history", json.toString()).commit()
        val loaded = loadedCorrection()
        assertNull(loaded.observedLeftReps)
        assertNull(loaded.observedRightReps)
        assertEquals(0, loaded.observedBothReps)
        assertNull(loaded.observedUnknownReps)
        assertNull(loaded.repMovementPattern)
    }
}
