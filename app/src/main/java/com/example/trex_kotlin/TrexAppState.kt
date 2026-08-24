package com.example.trex_kotlin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate

/**
 * 앱 서비스 상태의 단일 소유자.
 *
 * 화면(컴포저블)은 상태를 소유하지 않고 여기서 읽고 액션 메서드로만 바꾼다.
 * 모든 변경은 [TrexStore] 로 즉시 영속화된다 — 탭 전환/회전/재시작에도 기록이 유지된다.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TrexStore(application)

    // ---- 진행 플래그

    var guideDone by mutableStateOf(store.guideDone)
        private set
    var loggedIn by mutableStateOf(store.loggedIn)
        private set
    var onboarded by mutableStateOf(store.onboarded)
        private set

    fun completeGuide() {
        guideDone = true
        store.guideDone = true
    }

    fun completeLogin() {
        loggedIn = true
        store.loggedIn = true
    }

    fun completeOnboarding(profile: UserProfile) {
        this.profile = profile
        store.saveProfile(profile)
        onboarded = true
        store.onboarded = true
    }

    fun logout() {
        loggedIn = false
        store.loggedIn = false
    }

    // ---- 사용자 프로필 → 권장 영양 목표

    var profile by mutableStateOf(store.loadProfile() ?: UserProfile())
        private set

    val recommendedGoal: Nutrition get() = recommendedNutritionGoal(profile)

    /** 사용자가 직접 수정한 목표. null 이면 프로필 기반 추천값을 쓴다. */
    var goalOverride by mutableStateOf(store.loadGoalOverride())
        private set

    val targetGoal: Nutrition get() = goalOverride ?: recommendedGoal

    fun setTargetGoal(goal: Nutrition) {
        goalOverride = goal.normalizedGoal()
        store.saveGoalOverride(goalOverride)
    }

    fun resetTargetGoalToRecommended() {
        goalOverride = null
        store.saveGoalOverride(null)
    }

    // ---- 운동 계획 / 기록

    var workoutPlan by mutableStateOf(store.loadPlan() ?: todayPlan)
        private set

    var workoutHistory by mutableStateOf(store.loadHistory() ?: seedWorkoutHistory(todayPlan))
        private set

    fun updatePlan(plan: List<Workout>) {
        workoutPlan = plan
        store.savePlan(plan)
    }

    fun recordCompletedSession(elapsedSeconds: Int) {
        workoutHistory = workoutHistory.replaceTodayWith(
            createWorkoutHistoryDay(workoutPlan, elapsedSeconds),
        )
        store.saveHistory(workoutHistory)
    }

    val todayRecord: WorkoutHistoryDay?
        get() = workoutHistory.firstOrNull { it.epochDay == LocalDate.now().toEpochDay() }

    /** 오늘 포함 최근 연속 운동일. */
    fun attendanceStreak(): Int {
        val days = workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
        var streak = 0
        var cursor = LocalDate.now().toEpochDay()
        if (cursor !in days) cursor -= 1 // 오늘 아직 안 했으면 어제부터 센다
        while (cursor in days) {
            streak += 1
            cursor -= 1
        }
        return streak
    }

    // ---- 식단 (epochDay 기준 저장, 화면에는 오늘 기준 offset 으로 노출)

    var dietByDay by mutableStateOf(
        store.loadDiet() ?: mapOf(LocalDate.now().toEpochDay() to seedFoods()),
    )
        private set

    var waterByDay by mutableStateOf(store.loadWater() ?: emptyMap())
        private set

    private fun epochDayFor(offset: Int): Long = LocalDate.now().toEpochDay() + offset

    fun dietFor(offset: Int): Map<String, List<FoodEntry>> =
        dietByDay[epochDayFor(offset)] ?: emptyDietSlots()

    fun waterFor(offset: Int): Int = waterByDay[epochDayFor(offset)] ?: 0

    fun addWater(offset: Int) {
        val key = epochDayFor(offset)
        waterByDay = waterByDay + (key to (waterByDay[key] ?: 0) + 1)
        store.saveWater(waterByDay)
    }

    /** 슬롯에 음식 추가 (사진/수동 기록 플로우의 결과). */
    fun appendFoods(offset: Int, slot: String, foods: List<FoodEntry>) {
        mutateSlots(offset) { slots ->
            slots + (slot to slots[slot].orEmpty() + foods)
        }
    }

    /** 슬롯 전체 교체 (끼니 수정 시트의 결과). */
    fun replaceSlot(offset: Int, slot: String, foods: List<FoodEntry>) {
        mutateSlots(offset) { slots -> slots + (slot to foods) }
    }

    fun clearSlot(offset: Int, slot: String) = replaceSlot(offset, slot, emptyList())

    /** 마지막에 추가한 n개 음식 되돌리기 (기록 직후 실행 취소 토스트). */
    fun undoAppend(offset: Int, slot: String, count: Int) {
        mutateSlots(offset) { slots ->
            val current = slots[slot].orEmpty()
            slots + (slot to current.dropLast(count))
        }
    }

    private fun mutateSlots(
        offset: Int,
        transform: (Map<String, List<FoodEntry>>) -> Map<String, List<FoodEntry>>,
    ) {
        val key = epochDayFor(offset)
        val current = dietByDay[key] ?: emptyDietSlots()
        val keepFrom = LocalDate.now().toEpochDay() + DIET_MIN_OFFSET
        dietByDay = (dietByDay + (key to transform(current)))
            .filterKeys { it >= keepFrom }
        store.saveDiet(dietByDay)
    }

    /** 현재 끼니 슬롯의 가장 최근 기록 — "최근 기록 다시 기록하기" 소스. */
    fun recentFoodsForCurrentMeal(): List<FoodEntry> {
        val mealId = currentMealId()
        return dietByDay.entries
            .sortedByDescending { it.key }
            .firstNotNullOfOrNull { (_, slots) -> slots[mealId]?.takeIf { it.isNotEmpty() } }
            .orEmpty()
    }

    companion object {
        const val DIET_MIN_OFFSET = -7
        const val DIET_MAX_OFFSET = 0
    }
}
