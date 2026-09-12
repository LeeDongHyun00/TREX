package com.example.trex_kotlin

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 새 본운동은 끝의 회복 묶음 앞, 회복 운동은 맨 뒤에 추가한다. 기존 항목 순서는 유지한다. */
fun List<Workout>.insertBeforeRecovery(workout: Workout): List<Workout> {
    val index = if (workout.category == "회복") size else indexOfLast { it.category != "회복" } + 1
    return toMutableList().apply { add(index, workout) }
}

/** 추천은 현재 카탈로그의 주 사용 부위가 같은 항목이다. 난이도나 처방 적합도를 인증하지 않는다. */
internal fun workoutRegion(name: String, category: String): String = when {
    name in setOf("푸쉬업", "니 푸쉬업", "인클라인 푸쉬업", "벽 푸쉬업", "딥스") -> "가슴·팔"
    name in setOf("덤벨 컬", "바벨 컬") -> "팔"
    name in setOf("랫풀 다운", "밴드 로우") -> "등"
    name in setOf("오버헤드 프레스", "사이드 레터럴 레이즈", "프런트 레이즈", "업라이트로우", "Y 레이즈") -> "어깨"
    name in setOf("바벨 데드리프트", "굿모닝", "힙 쓰러스트", "글루트 브릿지", "힙 브릿지 홀드") -> "엉덩이·허벅지 뒤"
    name == "카프 레이즈" -> "종아리"
    category == "하체" || name == "스텝업" -> "허벅지·엉덩이"
    category in setOf("코어", "복근") -> "코어·복부"
    else -> category
}
internal fun recommendedReplacements(workout: Workout): List<WorkoutTemplate> {
    val region = workoutRegion(workout.name, workout.category)
    return workoutCatalog.values.flatten().filter { it.name != workout.name && workoutRegion(it.name, it.category) == region }
        .sortedByDescending { it.name == workout.alt?.name }.take(3)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutCatalogBrowser(current: Workout?, onPick: (WorkoutTemplate) -> Unit, modifier: Modifier = Modifier) {
    val c = Trex.c
    var query by remember { mutableStateOf("") }
    val tabs = listOf(if (current == null) "전체" else "추천") + workoutCatalog.keys
    var selected by remember { mutableIntStateOf(0) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val recommendations = remember(current?.name) { current?.let(::recommendedReplacements).orEmpty() }
    val results = when {
        query.isNotBlank() -> workoutCatalog.values.flatten().filter { it.name.contains(query.trim(), true) }
        tabs[selected] == "추천" -> recommendations
        tabs[selected] == "전체" -> workoutCatalog.values.flatten()
        else -> workoutCatalog[tabs[selected]].orEmpty()
    }.filter { it.name != current?.name }
    LaunchedEffect(selected, query) { state.scrollToItem(0) }
    Column(modifier.padding(top = 18.dp)) {
        DField(query, { query = it }, "운동 검색", modifier = Modifier.fillMaxWidth())
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp, containerColor = c.sheet,
            contentColor = c.primaryText, modifier = Modifier.padding(top = 8.dp),
            divider = { HorizontalDivider(color = c.line) }) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selected == i, onClick = { selected = i; query = "" },
                    modifier = Modifier.semantics { contentDescription = "$title 카테고리" },
                    text = { Text(title, fontSize = 14.sp, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal) })
            }
        }
        LazyColumn(Modifier.weight(1f), state = state) {
            if (query.isBlank() && tabs[selected] == "추천") item {
                Text("추천 대체 운동", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            }
            if (results.isEmpty()) item {
                Text(if (query.isNotBlank()) "검색 결과가 없어요." else "다른 카테고리에서 운동을 선택해 주세요.",
                    color = c.text2, modifier = Modifier.padding(vertical = 28.dp))
            }
            items(results.size, key = { results[it].name }) { i ->
                CatalogRow(results[i], onPick)
                if (i < results.lastIndex) HorizontalDivider(color = c.line)
            }
        }
    }
}
@Composable
private fun CatalogRow(template: WorkoutTemplate, onPick: (WorkoutTemplate) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(template) }.semantics { contentDescription = "${template.name} 선택" }
        .padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(template.name, color = Trex.c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = Trex.c.text3, modifier = Modifier.size(18.dp))
    }
}
