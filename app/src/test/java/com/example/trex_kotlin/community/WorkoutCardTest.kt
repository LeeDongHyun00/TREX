package com.example.trex_kotlin.community

import com.example.trex_kotlin.Workout
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 오운완 카드 숫자 — 날짜·운동 이름·완료 세트·총 시간·예상 kcal 만(설계 §3: 자세 판정·점수·헤드라인 없음). */
class WorkoutCardTest {
    private fun workout(id: String, name: String, done: Boolean) = Workout(id, name, "12회", "3세트", posture = true, category = "하체", done = done)

    @Test
    fun 카드는_완료_화면과_같은_숫자() {
        val plan = listOf(workout("a", "스쿼트", true), workout("b", "런지", true), workout("c", "플랭크", false))
        val card = workoutCardData(plan, elapsedSeconds = 1_845, kcal = 212, date = LocalDate(2026, 10, 29))
        assertEquals("10월 29일 목요일", card.dateLabel)
        assertEquals(listOf("스쿼트", "런지", "플랭크"), card.workouts)
        assertEquals(2, card.doneSets)
        assertEquals("30:45", card.duration)
        assertEquals(212, card.kcal)
    }

    @Test
    fun 같은_운동이_여러_번이면_이름은_한_번() {
        val plan = listOf(workout("a", "스쿼트", true), workout("b", "스쿼트", true))
        assertEquals(listOf("스쿼트"), workoutCardData(plan, 60, 10, LocalDate(2026, 1, 1)).workouts)
    }
}
