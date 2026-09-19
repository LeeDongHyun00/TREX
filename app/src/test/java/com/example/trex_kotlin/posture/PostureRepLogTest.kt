package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class PostureRepLogTest {
    private val leftRaw = RepRecord(11_400L, 31f, 171f, true)
    private val rightRaw = RepRecord(11_700L, 54f, 158f, false)

    private fun event(side: RepSide, left: RepRecord? = null, right: RepRecord? = null, common: RepRecord? = null): ExerciseRepEvent {
        val cycles = buildMap {
            if (left != null) put(RepSide.LEFT, ExerciseRepCycle("elbow_L", left.cycleMin, left.cycleMax, left.valid))
            if (right != null) put(RepSide.RIGHT, ExerciseRepCycle("elbow_R", right.cycleMin, right.cycleMax, right.valid))
            if (common != null) put(RepSide.UNKNOWN, ExerciseRepCycle("knee_mean", common.cycleMin, common.cycleMax, common.valid))
        }
        return ExerciseRepEvent(listOfNotNull(left, right, common).maxOf { it.tMs }, side,
            ExerciseRepCounts(), cycles, left, right, common)
    }

    private fun log(records: List<RepRecord>? = null, summary: RepObservationSummary? = null, count: Int? = null): SetLog =
        SetLog.build("덤벨 컬", emptyList(), emptyList(), "mp_v0", "full", "GPU", true, 300,
            now = Date(0), repCount = count, repTimesMs = records?.map { it.tMs }, repSignal = "elbow_L / elbow_R",
            repRecords = records, observedReps = summary)

    @Test fun legacyLogsKeepExactRepFieldsAndDoNotInventSideMetadata() {
        val json = SetLogJson.encode(log(listOf(RepRecord(1400, .4f, 1.4f, null)), count = 1))
        val reps = json.substringAfter("\"reps\":").substringBefore(",\"frames\":")
        assertEquals("{\"count\":1,\"invalid\":0,\"signal\":\"elbow_L / elbow_R\",\"t_ms\":[1400],\"min\":[0.4],\"max\":[1.4],\"valid\":[null]}", reps)
        for (field in listOf("pattern", "total", "left", "right", "both", "unknown", "events", "details")) {
            assertFalse(json.contains("\"$field\":"))
        }
        assertFalse(SetLogJson.encode(log()).contains("\"reps\":"))
    }

    @Test fun pairedRepHasOneTargetCountAndKeepsTwoRawExtremaAndTimes() {
        val original = event(RepSide.BOTH, leftRaw, rightRaw).toRepRecord()
        val record = original.relativeTo(10_000)
        assertTrue(record.cycleMin.isNaN())
        assertTrue(record.cycleMax.isNaN())
        assertNull(record.valid)
        assertEquals(1700L, record.tMs)
        val details = requireNotNull(record.details)
        assertEquals(1400L, details.getValue(RepSide.LEFT).tMs)
        assertEquals(1700L, details.getValue(RepSide.RIGHT).tMs)
        assertEquals(11_400L, original.details!!.getValue(RepSide.LEFT).tMs)
        val summary = RepObservationSummary("SIMULTANEOUS", 1, 1, 1, 1, 0)
        val built = log(listOf(record), summary)
        assertEquals(1, built.repCount) // 왼쪽+오른쪽=2를 총수로 다시 합산하지 않는다.
        val json = SetLogJson.encode(built)
        assertTrue(json.contains("\"count\":1"))
        assertTrue(json.contains("\"min\":[null],\"max\":[null],\"valid\":[null]"))
        assertTrue(json.contains("\"pattern\":\"SIMULTANEOUS\",\"total\":1,\"left\":1,\"right\":1,\"both\":1,\"unknown\":0"))
        assertTrue(json.contains("\"events\":[{\"t_ms\":1700,\"side\":\"BOTH\",\"min\":null,\"max\":null,\"valid\":null,"))
        assertTrue(json.contains("\"LEFT\":{\"signal\":\"elbow_L\",\"t_ms\":1400,\"min\":31,\"max\":171,\"valid\":true}"))
        assertTrue(json.contains("\"RIGHT\":{\"signal\":\"elbow_R\",\"t_ms\":1700,\"min\":54,\"max\":158,\"valid\":false}"))
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test fun eachSideRetainsTwoEventsEvenWhenTheyFinishInTheSameFrame() {
        val records = listOf(event(RepSide.LEFT, left = leftRaw), event(RepSide.RIGHT, right = rightRaw.copy(tMs = leftRaw.tMs)))
            .map { it.toRepRecord().relativeTo(10_000) }
        val json = SetLogJson.encode(log(records, RepObservationSummary("ALTERNATING_EACH", 2, 1, 1, 0, 0)))
        assertTrue(json.contains("\"count\":2"))
        assertTrue(json.contains("\"t_ms\":[1400,1400],\"min\":[31,54],\"max\":[171,158],\"valid\":[true,false]"))
        assertTrue(json.contains("\"side\":\"LEFT\""))
        assertTrue(json.contains("\"side\":\"RIGHT\""))
        assertFalse(json.contains("\"side\":\"BOTH\""))
        assertEquals(2, records.size)
    }

    @Test fun unknownWholeCycleKeepsOriginalRecordWithoutGuessingAnatomicalSide() {
        val raw = RepRecord(11_500, 72f, 167f, false)
        val record = event(RepSide.UNKNOWN, common = raw).toRepRecord()
        assertEquals(raw.tMs, record.tMs)
        assertEquals(raw.cycleMin, record.cycleMin, 0f)
        assertEquals(raw.cycleMax, record.cycleMax, 0f)
        assertEquals(raw.valid, record.valid)
        assertEquals(setOf(RepSide.UNKNOWN), record.details!!.keys)
        val json = SetLogJson.encode(log(listOf(record.relativeTo(10_000)), RepObservationSummary("ALTERNATING_EACH", 1, 0, 0, 0, 1)))
        assertTrue(json.contains("\"total\":1,\"left\":0,\"right\":0,\"both\":0,\"unknown\":1"))
        assertTrue(json.contains("\"side\":\"UNKNOWN\""))
        assertTrue(json.contains("\"UNKNOWN\":{\"signal\":\"knee_mean\",\"t_ms\":1500,\"min\":72,\"max\":167,\"valid\":false}"))
    }

    @Test fun legacyRelativeTimesPreserveAbsenceAndDoNotClampOrModifyTheSource() {
        val raw = RepRecord(10_500, .3f, 1.4f, null)
        val relative = raw.relativeTo(11_000)
        assertEquals(-500L, relative.tMs)
        assertNull(relative.side)
        assertNull(relative.details)
        assertEquals(10_500L, raw.tMs)
    }

    @Test fun newSummaryDoesNotOverwriteAnExplicitLegacyCount() {
        val json = SetLogJson.encode(log(summary = RepObservationSummary("SIMULTANEOUS", 1, 1, 1, 1, 0), count = 9))
        assertTrue(json.contains("\"count\":9"))
        assertTrue(json.contains("\"total\":1"))
        assertFalse(json.contains("\"events\":"))
    }

    @Test fun detailSignalsAreEscapedAndNonFiniteRawValuesBecomeJsonNull() {
        val e = event(RepSide.LEFT, left = leftRaw.copy(cycleMax = Float.POSITIVE_INFINITY))
        val edited = e.copy(cycles = mapOf(RepSide.LEFT to e.cycles.getValue(RepSide.LEFT).copy(signalFeature = "elbow_\"L\"\n")))
        val json = SetLogJson.encode(log(listOf(edited.toRepRecord()), count = 1))
        assertTrue(json.contains("\"signal\":\"elbow_\\\"L\\\"\\n\""))
        assertTrue(json.contains("\"max\":null"))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains("Infinity"))
    }
}
