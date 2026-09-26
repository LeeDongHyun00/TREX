package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class WorkoutListPolicyTest {
    private fun w(id: String, category: String = "하체") = Workout(id,id,"12회 × 3세트","8분",false,category)
    @Test fun insertionKeepsTrailingCooldownAndExistingOrder() {
        val plan=listOf(w("a"),w("b"),w("c","회복"),w("d","회복"))
        assertEquals(listOf("a","b","new","c","d"),plan.insertBeforeRecovery(w("new")).map { it.id })
        assertEquals(listOf("a","b","c","d","new"),plan.insertBeforeRecovery(w("new","회복")).map { it.id })
        assertEquals(listOf("a","b","new"),plan.take(2).insertBeforeRecovery(w("new")).map { it.id })
        assertEquals(listOf("new"),emptyList<Workout>().insertBeforeRecovery(w("new")).map { it.id })
    }
    @Test fun reorderUsesIdentityAndKeepsSettingsInBothDirections() {
        val a=w("a").copy(posture=true,restSeconds=120)
        val plan=listOf(a,w("b"),w("c"))
        val down=plan.moveWorkout("a","c")
        assertEquals(listOf("b","c","a"),down.map { it.id });assertEquals(a,down.last())
        assertEquals(plan,down.moveWorkout("a","b"));assertEquals(plan,plan.moveWorkout("missing","b"))
    }
    @Test fun recommendationsDoNotTreatAllUpperBodyExercisesAsInterchangeable() {
        val push=Workout("p","푸쉬업","12회","5분",true,"상체")
        val recs=recommendedReplacements(push)
        assertTrue(recs.isNotEmpty());assertTrue(recs.all { workoutRegion(it.name,it.category)=="가슴·팔" })
        assertFalse(recs.any { it.name in listOf("푸쉬업","덤벨 컬","랫풀 다운") })
    }
}
