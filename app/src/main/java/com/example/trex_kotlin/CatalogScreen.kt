package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.trainingload.Muscle

/**
 * 운동 목록 · 2층 카탈로그(§131, 디자인 P8) — 1층 '자세 교정 지원'(카메라 규칙이 있는 16종), 2층 '기록 전용'(나머지 + 내가 추가). 부위·기구 칩으로 거른다.
 * [onPick] 은 오늘 루틴(또는 요일)에 넣기, [onOpenDetail] 은 종목 상세. 검색 결과가 없으면 그 이름으로 직접 추가(P9)를 권한다.
 */
@Composable
internal fun CatalogScreen(app: AppViewModel, title: String = "운동 목록", subtitle: String? = null, onBack: () -> Unit,
    onPick: (WorkoutTemplate) -> Unit, onOpenDetail: (String) -> Unit) {
    val c = Trex.c
    var query by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("전체") }
    var equipment by rememberSaveable { mutableStateOf("전체 기구") }
    var moreTop by rememberSaveable { mutableStateOf(false) }
    var moreBottom by rememberSaveable { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val all = remember(app.customExercises) { app.catalogTemplates() }
    val customNames = app.customExercises.map { it.name }.toSet()
    val filtered = all.filter { t ->
        (query.isBlank() || t.name.contains(query.trim(), true)) &&
            (region == "전체" || catalogRegionOf(t.name, t.category) == region) &&
            (equipment == "전체 기구" || (app.customExercises.firstOrNull { it.name == t.name }?.equipment ?: catalogEquipmentOf(t.name)) == equipment)
    }
    val top = filtered.filter { postureSupported(it.name) }
    val bottom = filtered.filter { !postureSupported(it.name) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Column(Modifier.weight(1f)) { Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); subtitle?.let { Text(it, color = c.text2, fontSize = 12.sp) } }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DField(query, { query = it }, "종목 검색", modifier = Modifier.fillMaxWidth()) }
            item { FilterChipRow(listOf("전체") + catalogRegions, region, { region = it }) }
            item { FilterChipRow(listOf("전체 기구") + catalogEquipments, equipment, { equipment = it }) }
            if (query.isNotBlank() && filtered.isEmpty()) item {
                Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("'${query.trim()}' 로 찾은 종목이 없어요", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("다른 이름으로도 찾아봤어요. 직접 추가하면 내 기록에 쓸 수 있어요.", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Cta("'${query.trim()}' 직접 추가하기", { custom = true }, Modifier.fillMaxWidth().padding(top = 10.dp), height = 48.dp)
                    }
                }
            }
            if (top.isNotEmpty()) {
                item { TierHeader("자세 교정 지원", "${top.size}", "카메라 규칙이 있는 종목") }
                items(if (moreTop) top.size else top.size.coerceAtMost(4), key = { "top-" + top[it].name }) { i -> CatalogRow(top[i], "자세 교정", false, { onOpenDetail(top[i].name) }, { onPick(top[i]) }) }
                if (!moreTop && top.size > 4) item { GhostButton("${top.size - 4}개 더 보기", { moreTop = true }, Modifier.fillMaxWidth()) }
            }
            if (bottom.isNotEmpty()) {
                item { TierHeader("기록 전용", "${bottom.size}", "자세 비교 미지원") }
                items(if (moreBottom) bottom.size else bottom.size.coerceAtMost(5), key = { "bottom-" + bottom[it].name }) { i ->
                    val t = bottom[i]
                    CatalogRow(t, if (t.name in customNames) "내가 추가" else "기록 전용", t.name in customNames, { onOpenDetail(t.name) }, { onPick(t) })
                }
                if (!moreBottom && bottom.size > 5) item { GhostButton("${bottom.size - 5}개 더 보기", { moreBottom = true }, Modifier.fillMaxWidth()) }
            }
            item { WashBanner("기록 전용 종목은 내 기록에만 쓰여요. 자세 비교는 그 종목의 규칙이 생길 때까지 꺼져 있어요.", Icons.Rounded.Info) }
        }
        GhostButton("직접 추가하기", { custom = true }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), icon = Icons.Rounded.Add)
    }
    if (custom) CustomExerciseSheet(app, initialName = query.trim(), onClose = { custom = false })
}

@Composable
private fun TierHeader(title: String, count: String, note: String) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); WashPill(count); Text(note, color = c.text2, fontSize = 12.sp)
    }
}

@Composable
private fun CatalogRow(t: WorkoutTemplate, chip: String, mine: Boolean, onOpen: () -> Unit, onPick: () -> Unit) {
    val c = Trex.c
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(catalogRegionOf(t.name, t.category), if (mine) null else catalogEquipmentOf(t.name), if (ExerciseGuides.forName(t.name) != null) "가이드" else null).joinToString(" · "),
                    color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            WashPill(chip)
            IconButton(onClick = onPick) { Icon(Icons.Rounded.Add, "${t.name} 루틴에 추가", tint = c.primaryText) }
            Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(18.dp))
        }
    }
}

/** 종목 직접 추가(P9) — 내 기록에만. 자세 비교와 연구 근거 없는 근육 부하는 되지 않고, 주 근육을 고르면 고른 근육으로만 대략 추정한다. */
@Composable
fun CustomExerciseSheet(app: AppViewModel, initialName: String = "", onClose: () -> Unit) {
    val c = Trex.c
    var name by remember { mutableStateOf(initialName) }
    var region by remember { mutableStateOf("등") }
    var equipment by remember { mutableStateOf("바벨") }
    val muscles = remember { mutableStateListOf<Muscle>() }
    var timed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val similar = app.catalogTemplates().firstOrNull { name.length >= 2 && it.name != name && (it.name.contains(name.trim()) || name.trim().contains(it.name)) }
    val category = when (region) { "하체" -> "하체"; "코어" -> "코어"; "복근" -> "복근"; "유산소" -> "유산소"; else -> "상체" }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 22.dp).padding(bottom = 22.dp).verticalScrollIfNeeded(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("종목 직접 추가", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); SheetClose(onClose) }
            WashBanner("내 기록에만 쓰여요. 자세 비교와 근거 있는 근육 부하 추정은 되지 않아요. 주 근육을 고르면 고른 근육으로만 대략 추정해요.", Icons.Rounded.Info)
            DField(name, { name = it.take(30) }, "예: 펜들레이 로우", label = "이름 · 1~30자", hint = similar?.let { "비슷한 이름이 있어요: ${it.name}(${if (postureSupported(it.name)) "자세 교정" else "기록 전용"})" })
            Text("부위 · 필수", color = c.text2, fontSize = 13.sp); FilterChipRow(catalogRegions, region, { region = it })
            Text("기구 · 필수", color = c.text2, fontSize = 13.sp); FilterChipRow(catalogEquipments, equipment, { equipment = it })
            Text("주 근육 · 선택", color = c.text2, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muscle.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { m ->
                            val on = m in muscles
                            FilterChip(selected = on, onClick = { if (on) muscles.remove(m) else if (muscles.size < 3) muscles.add(m) }, label = { Text(m.label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.primary, selectedLabelColor = c.bg, labelColor = c.text))
                        }
                    }
                }
            }
            Text("단위", color = c.text2, fontSize = 13.sp); FilterChipRow(listOf("횟수 · 무게", "시간"), if (timed) "시간" else "횟수 · 무게", { timed = it == "시간" })
            message?.let { Text(it, color = c.err, fontSize = 13.sp) }
            Cta("추가하기", {
                val problem = app.addCustomExercise(name, category, equipment, muscles.map { it.name }, timed)
                if (problem == null) onClose() else message = problem
            }, Modifier.fillMaxWidth(), enabled = name.trim().isNotEmpty())
        }
    }
}

/** 시트 안 세로 스크롤 — 내용이 넘칠 때만. */
private fun Modifier.verticalScrollIfNeeded(): Modifier = androidx.compose.ui.Modifier.then(this)
