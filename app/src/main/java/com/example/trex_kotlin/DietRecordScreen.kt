package com.example.trex_kotlin

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun DietRecordLink(onClick: () -> Unit) {
    val c = Trex.c
    Surface(onClick = onClick, color = c.surface, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = c.primaryText, modifier = Modifier.size(21.dp))
            Text("최근 7일 식단 기록", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = c.text2)
        }
    }
}

/** 절대 날짜로 조회한다. 과거 식사에 오늘의 영양 목표를 소급해서 붙이지 않는다. */
@Composable
fun DietRecordScreen(app: AppViewModel, onBack: () -> Unit) {
    val c = Trex.c
    val today = app.calendarDay
    var selected by rememberSaveable { mutableLongStateOf(today) }
    LaunchedEffect(today) { selected = selected.coerceIn(today - 6, today) }
    val days = (6 downTo 0).map { LocalDate.ofEpochDay(today - it) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Text("식단 기록", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, top = 22.dp))
        Text("최근 7일", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 22.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { day ->
                val active = day.toEpochDay() == selected
                val recorded = app.dietByDay[day.toEpochDay()]?.values?.any { it.isNotEmpty() } == true
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (active) c.primaryWash else c.bg)
                    .selectable(active, role = Role.Tab, onClick = { selected = day.toEpochDay() })
                    .semantics { contentDescription = "${day.monthValue}월 ${day.dayOfMonth}일 식단${if (recorded) ", 기록 있음" else ", 기록 없음"}" }
                    .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (day.toEpochDay() == today) "오늘" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                        color = if (active) c.primaryText else c.text2, fontSize = 11.sp)
                    Text("${day.dayOfMonth}", color = if (active) c.primaryText else c.text, fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp, bottom = 8.dp))
                    Box(Modifier.size(4.dp).background(if (recorded) c.primaryText else c.line, RoundedCornerShape(2.dp)))
                }
            }
        }
        AnimatedContent(selected, modifier = Modifier.weight(1f), transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { if (targetState > initialState) 24 else -24 }) togetherWith fadeOut(tween(120))
        }, label = "diet-record-date") { day ->
            val date = LocalDate.ofEpochDay(day)
            val slots = app.dietByDay[day].orEmpty()
            val entries = slots.values.flatten()
            val total = entries.totalNutrition()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp)) {
                item {
                    Text("${date.monthValue}월 ${date.dayOfMonth}일", color = c.text2, fontSize = 14.sp)
                    if (entries.isEmpty()) {
                        Text("기록된 식사가 없어요.", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 30.dp, bottom = 20.dp))
                    } else {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                            Text("${total.kcal}", color = c.text, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                            Text(" kcal", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            DietMacro("탄수화물", total.carb)
                            DietMacro("단백질", total.protein)
                            DietMacro("지방", total.fat)
                        }
                    }
                }
                mealMetas.forEach { meal ->
                    val foods = slots[meal.id].orEmpty()
                    if (foods.isNotEmpty()) item(key = meal.id) {
                        Text(meal.label, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                        foods.forEach { food ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(food.name + if (food.qty > 1) " ×${food.qty}" else "", color = c.text,
                                    fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 16.dp))
                                Text("${food.nutrition.kcal * food.qty} kcal", color = c.text2, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onBack, modifier = Modifier.heightIn(min = 52.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.surface, contentColor = c.text)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, modifier = Modifier.size(20.dp))
                Text("식단으로", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun DietMacro(label: String, grams: Double) {
    Column {
        Text(label, color = Trex.c.text2, fontSize = 12.sp)
        Text("${grams.toInt()} g", color = Trex.c.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    }
}
