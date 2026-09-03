package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 자가 라벨 저장 — rep_replay.py 가 읽는 rep_truth.csv 형식(헤더 한 번, 렙 있는 행만)과 맞고, 세트 로그 규약과 충돌하지 않아야 한다. */
class SetLabelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun label(setId: String, reps: Int?, form: FormLabel?, exercise: String = "바벨 스쿼트", source: String? = if (reps != null) "edited" else null) =
        SetSelfLabel(setId, exercise, reps, source, form, "2026-09-02T01:02:03Z")

    @Test
    fun writesHeaderOnceAndOneRowPerLabeledSet() {
        val store = SetLabelStore(tmp.newFolder("posture_logs"))
        store.append(label("s1", 8, FormLabel.GOOD))
        store.append(label("s2", 10, FormLabel.INTENDED, exercise = "오버 헤드 프레스", source = "confirmed"))
        val lines = store.truthFile.readLines(Charsets.UTF_8)
        assertEquals(3, lines.size)
        assertEquals("set_id,reps_min,reps_max,exercise,form,source,created_at", lines[0])
        assertEquals("s1,8,8,바벨 스쿼트,good,edited,2026-09-02T01:02:03Z", lines[1])
        assertEquals("s2,10,10,오버 헤드 프레스,intended,confirmed,2026-09-02T01:02:03Z", lines[2])
        assertEquals(1, lines.count { it.startsWith("set_id,") })
        // reps_min == reps_max
        for (l in lines.drop(1)) {
            val c = l.split(',')
            assertEquals(c[1], c[2])
        }
        assertEquals(2, store.count())
    }

    @Test
    fun repsNullGoesToJsonlOnly() {
        val store = SetLabelStore(tmp.newFolder("posture_logs"))
        store.append(label("s1", null, FormLabel.BROKE))
        assertFalse(store.truthFile.exists())
        assertEquals(1, store.count())
        val json = store.labelsFile.readLines(Charsets.UTF_8).single()
        assertEquals(
            "{\"set_id\":\"s1\",\"exercise\":\"바벨 스쿼트\",\"actual_reps\":null,\"reps_source\":null,\"form\":\"broke\",\"created_at\":\"2026-09-02T01:02:03Z\"}",
            json,
        )
        // 이후 렙 있는 라벨이 오면 그때 헤더가 처음 써진다
        store.append(label("s2", 6, null))
        val lines = store.truthFile.readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        assertEquals("s2,6,6,바벨 스쿼트,,edited,2026-09-02T01:02:03Z", lines[1])
        assertEquals(2, store.count())
        assertTrue(store.labelsFile.readLines(Charsets.UTF_8)[1].contains("\"form\":null"))
        assertTrue(store.labelsFile.readLines(Charsets.UTF_8)[1].contains("\"reps_source\":\"edited\""))
    }

    @Test
    fun csvQuotesCommaAndQuote() {
        val store = SetLabelStore(tmp.newFolder("posture_logs"))
        store.append(label("s1", 3, FormLabel.GOOD, exercise = "덤벨 \"컬\", 양손"))
        val row = store.truthFile.readLines(Charsets.UTF_8)[1]
        assertEquals("s1,3,3,\"덤벨 \"\"컬\"\", 양손\",good,edited,2026-09-02T01:02:03Z", row)
        assertEquals("plain", SetLabelStore.csv("plain"))
    }

    @Test
    fun countIsZeroWithoutFile() {
        val store = SetLabelStore(tmp.newFolder("posture_logs"))
        assertEquals(0, store.count())
    }

    /** 라벨 파일이 세트 로그 저장소의 `*.jsonl` 규약에 잡히면 랩 '지우기'가 라벨을 삭제하고 세트 수에 라벨 줄이 섞인다. */
    @Test
    fun labelsLiveOutsideSetLogGlob() {
        val dir = tmp.newFolder("posture_logs")
        val labels = SetLabelStore(dir)
        val logs = SetLogStore(dir)
        labels.append(label("s1", 8, FormLabel.GOOD))
        labels.append(label("s2", null, FormLabel.BROKE))
        assertTrue(labels.labelsFile.exists())
        assertTrue(labels.truthFile.exists())
        assertTrue(logs.files().isEmpty())
        assertEquals(0, logs.totalSets())
        logs.clear()
        assertTrue(labels.labelsFile.exists())
        assertTrue(labels.truthFile.exists())
        assertEquals(2, labels.count())
    }
}
