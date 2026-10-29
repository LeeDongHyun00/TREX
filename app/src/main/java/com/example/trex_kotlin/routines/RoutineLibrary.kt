package com.example.trex_kotlin.routines

import com.example.trex_kotlin.Workout

/**
 * 내 루틴 보관함의 루틴 하나(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 직접 저장했거나 링크로 받은 것.
 * [from] 은 받은 루틴의 보낸 사람 닉네임(직접 저장했으면 null). 계정 백업 항목(`routines`)이다.
 */
data class SavedRoutine(
    val id: String,
    val name: String,
    val workouts: List<Workout>,
    val from: String?,
    val savedAtMillis: Long,
)

const val ROUTINE_LIBRARY_MAX = 50

/** 맨 앞(최근)에 넣는다. 이미 [ROUTINE_LIBRARY_MAX]개면 null — "보관함이 가득 찼어요". */
fun List<SavedRoutine>.withAdded(r: SavedRoutine): List<SavedRoutine>? = if (size >= ROUTINE_LIBRARY_MAX) null else listOf(r) + this

fun List<SavedRoutine>.without(id: String): List<SavedRoutine> = filterNot { it.id == id }

/** 오늘 루틴으로 바꿀 때 — 새 id(`shared-$nowMillis-$순번`)로, 완료 표시 없이. */
fun SavedRoutine.toTodayPlan(nowMillis: Long): List<Workout> =
    workouts.mapIndexed { i, w -> w.copy(id = "shared-$nowMillis-$i", done = false) }
