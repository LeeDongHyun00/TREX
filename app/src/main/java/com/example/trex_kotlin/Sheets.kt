package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AltSuggestSheet(
    workout: Workout,
    onApply: (WorkoutAlt) -> Unit,
    onClose: () -> Unit,
) {
    var selectedAlt by remember(workout.id) { mutableStateOf<WorkoutAlt?>(null) }
    val fallbacks = mapOf(
        "하체" to listOf(WorkoutAlt("글루트 브릿지", "12회 x 3세트"), WorkoutAlt("카프 레이즈", "15회 x 3세트")),
        "코어" to listOf(WorkoutAlt("버드독", "10회 x 3세트"), WorkoutAlt("사이드 플랭크", "30초 x 3세트")),
        "복근" to listOf(WorkoutAlt("데드버그", "12회 x 3세트"), WorkoutAlt("사이드 플랭크", "30초 x 3세트")),
        "상체" to listOf(WorkoutAlt("니 푸쉬업", "10회 x 3세트"), WorkoutAlt("밴드 로우", "12회 x 3세트")),
        "유산소" to listOf(WorkoutAlt("제자리 걷기", "60초 x 4세트"), WorkoutAlt("스텝업", "12회 x 3세트")),
        "회복" to listOf(WorkoutAlt("캣카우 스트레칭", "전신 5분"), WorkoutAlt("차일드 포즈", "전신 4분")),
    )
    val alts = buildList {
        workout.alt?.let(::add)
        addAll(fallbacks[workout.category].orEmpty())
    }

    SheetSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("추천 대체 운동", color = TrexLime)
                    ScreenTitle(workout.name)
                }
            }
            Text(
                text = "${workout.category} · ${workout.reps} 와 비슷한 강도예요",
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(
                modifier = Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                alts.forEachIndexed { index, alt ->
                    val selected = selectedAlt == alt
                    Surface(
                        onClick = { selectedAlt = alt },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) TrexLime else Color.White.copy(alpha = 0.05f),
                        contentColor = if (selected) TrexDark else Color.White,
                        border = if (selected) null else dimBorder(),
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) TrexDark else TrexLime),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.FitnessCenter,
                                    contentDescription = null,
                                    tint = if (selected) TrexLime else TrexDark,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Text(
                                    alt.name,
                                    color = if (selected) TrexDark else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    alt.reps,
                                    color = if (selected) TrexDark.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.52f),
                                    fontSize = 11.sp,
                                )
                            }
                            if (index == 0) {
                                Pill(
                                    "가장 추천",
                                    background = if (selected) TrexDark else TrexLime,
                                    color = if (selected) TrexLime else TrexDark,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(
                                if (selected) Icons.Rounded.Check else Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = if (selected) TrexDark else TrexLime,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrexButton(
                    text = if (selectedAlt == null) "그대로 유지" else "변경하기",
                    onClick = {
                        selectedAlt?.let(onApply)
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                    container = if (selectedAlt == null) Color.White.copy(alpha = 0.1f) else TrexLime,
                    contentColor = if (selectedAlt == null) Color.White.copy(alpha = 0.72f) else TrexDark,
                    icon = if (selectedAlt == null) null else Icons.Rounded.Check,
                )
                IconCircleButton(
                    icon = Icons.Rounded.Close,
                    onClick = onClose,
                    size = 52.dp,
                    background = TrexError.copy(alpha = 0.12f),
                    contentColor = Color(0xFFFF8A8A),
                    contentDescription = "닫기",
                )
            }
        }
    }
}

@Composable
fun ManualFoodLogSheet(
    modeAdd: Boolean,
    initialSlot: String,
    initialFoods: List<FoodEntry>,
    onSave: (slot: String, foods: List<FoodEntry>) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var slot by remember(initialSlot) { mutableStateOf(initialSlot) }
    var foods by remember(initialSlot, initialFoods) { mutableStateOf(initialFoods) }
    var adding by remember(initialSlot, initialFoods) { mutableStateOf(modeAdd && initialFoods.isEmpty()) }
    val total by remember(foods) { derivedStateOf { foods.totalNutrition() } }

    SheetSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Column {
                SectionLabel("끼니 식단 기록", color = TrexLime)
                ScreenTitle("${mealMetas.first { it.id == slot }.label} 기록")
            }

            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mealMetas.forEach { meta ->
                    val active = slot == meta.id
                    Surface(
                        onClick = {
                            if (modeAdd) slot = meta.id
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            active -> TrexLime
                            modeAdd -> Color.White.copy(alpha = 0.08f)
                            else -> Color.White.copy(alpha = 0.04f)
                        },
                        contentColor = when {
                            active -> TrexDark
                            modeAdd -> Color.White.copy(alpha = 0.86f)
                            else -> Color.White.copy(alpha = 0.32f)
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(meta.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (foods.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 22.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("기록된 음식 ${foods.size}개", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${total.kcal} kcal", color = TrexLime, fontSize = 11.sp)
                }
                Column(
                    modifier = Modifier.padding(top = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    foods.forEach { food ->
                        FoodEntryRow(
                            food = food,
                            onRemove = { foods = foods - food },
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MacroStat("탄수", "${total.carb.toInt()}g", Modifier.weight(1f))
                    MacroStat("단백질", "${total.protein.toInt()}g", Modifier.weight(1f))
                    MacroStat("지방", "${total.fat.toInt()}g", Modifier.weight(1f))
                }
            }

            if (adding) {
                FoodAdder(
                    onAdd = {
                        foods = foods + it
                        adding = false
                    },
                    onCancel = { adding = false },
                    canCancel = foods.isNotEmpty(),
                )
            } else {
                Surface(
                    onClick = { adding = true },
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    border = dimBorder(0.16f),
                    contentColor = Color.White.copy(alpha = 0.86f),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("음식 추가", fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (!modeAdd) {
                TrexButton(
                    text = "삭제하기",
                    onClick = onDelete,
                    icon = Icons.Rounded.Delete,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    container = TrexError.copy(alpha = 0.12f),
                    contentColor = Color(0xFFFF8A8A),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrexButton(
                    text = if (modeAdd) "기록 추가" else "수정 완료",
                    onClick = { onSave(slot, foods) },
                    enabled = foods.isNotEmpty(),
                    icon = if (modeAdd) Icons.Rounded.Add else Icons.Rounded.Check,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(
                    icon = Icons.Rounded.Close,
                    onClick = onClose,
                    size = 52.dp,
                    background = TrexError.copy(alpha = 0.12f),
                    contentColor = Color(0xFFFF8A8A),
                    contentDescription = "닫기",
                )
            }
        }
    }
}

@Composable
fun PhotoFoodFlow(onClose: () -> Unit) {
    var stage by remember { mutableStateOf(FoodStage.Choose) }

    LaunchedEffect(stage) {
        if (stage == FoodStage.Analyzing) {
            delay(1800)
            stage = FoodStage.Result
        }
    }

    val detected = listOf(
        FoodEntry("현미밥", Nutrition(220, 46.0, 5.0, 1.7)),
        FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6)),
        FoodEntry("샐러드", Nutrition(120, 8.0, 4.0, 7.0)),
    )
    val total = detected.totalNutrition()

    SheetSurface {
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "photo-food-stage",
        ) { currentStage ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel(
                            text = when (currentStage) {
                                FoodStage.Choose -> "사진 식단 기록"
                                FoodStage.Capture -> "사진 확인"
                                FoodStage.Analyzing -> "AI 분석 중"
                                FoodStage.Result -> "분석 완료"
                            },
                            color = TrexLime,
                        )
                        ScreenTitle(
                            text = when (currentStage) {
                                FoodStage.Choose -> "사진으로 빠르게"
                                FoodStage.Capture -> "이 사진으로 분석할까요?"
                                FoodStage.Analyzing -> "잠시만 기다려주세요"
                                FoodStage.Result -> "${detected.size}가지 음식을 찾았어요"
                            },
                        )
                    }
                    CloseButton(onClick = onClose)
                }

                when (currentStage) {
                    FoodStage.Choose -> PhotoChoose(onCamera = { stage = FoodStage.Capture }, onGallery = { stage = FoodStage.Capture })
                    FoodStage.Capture -> PhotoCapture(onRetry = { stage = FoodStage.Choose }, onAnalyze = { stage = FoodStage.Analyzing })
                    FoodStage.Analyzing -> PhotoAnalyzing()
                    FoodStage.Result -> PhotoResult(total = total, detected = detected, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun PhotoChoose(onCamera: () -> Unit, onGallery: () -> Unit) {
    Text(
        text = "찍거나 가져온 음식 사진을 분석해서 영양 정보를 자동으로 채워드려요",
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Column(
        modifier = Modifier.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhotoAction(
            title = "사진 찍기",
            subtitle = "카메라로 바로 촬영",
            icon = Icons.Rounded.PhotoCamera,
            primary = true,
            onClick = onCamera,
        )
        PhotoAction(
            title = "갤러리에서 선택",
            subtitle = "앨범에서 음식 사진 가져오기",
            icon = Icons.Rounded.Image,
            primary = false,
            onClick = onGallery,
        )
    }
    Surface(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = dimBorder(),
        contentColor = Color.White.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = TrexLime, modifier = Modifier.size(15.dp))
            Text("여러 음식이 한 접시에 있어도 자동으로 분리해서 인식해요", fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 9.dp))
        }
    }
}

@Composable
private fun PhotoCapture(onRetry: () -> Unit, onAnalyze: () -> Unit) {
    FoodPhotoMock(modifier = Modifier.padding(top = 18.dp))
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrexButton(
            text = "다시 선택",
            onClick = onRetry,
            icon = Icons.Rounded.Refresh,
            modifier = Modifier.weight(1f),
            container = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White.copy(alpha = 0.85f),
        )
        TrexButton(
            text = "분석 시작",
            onClick = onAnalyze,
            icon = Icons.Rounded.AutoAwesome,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PhotoAnalyzing() {
    Box(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF54673E), Color(0xFF151A12)))),
    ) {
        FoodPlate(modifier = Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, TrexDark.copy(alpha = 0.86f))))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TrexLime),
                )
                Text("음식을 인식하고 있어요...", color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            LinearProgressIndicator(
                progress = { 0.86f },
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = TrexLime,
                trackColor = Color.White.copy(alpha = 0.16f),
            )
        }
    }
    Row(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("인식", "분류", "영양 계산").forEachIndexed { index, label ->
            MacroStat("${index + 1}단계", label, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhotoResult(total: Nutrition, detected: List<FoodEntry>, onClose: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TrexLime,
        contentColor = TrexDark,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("총 칼로리", color = TrexDark.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Pill("점심", background = TrexDark, color = TrexLime)
            }
            Row(
                modifier = Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(total.kcal.toString(), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(" kcal", color = TrexDark.copy(alpha = 0.68f), modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MacroStat("탄수", "${total.carb.toInt()}g", Modifier.weight(1f), light = true)
                MacroStat("단백질", "${total.protein.toInt()}g", Modifier.weight(1f), light = true)
                MacroStat("지방", "${total.fat.toInt()}g", Modifier.weight(1f), light = true)
            }
        }
    }
    Text("인식된 음식", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        detected.forEachIndexed { index, food ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = dimBorder(),
            ) {
                Row(
                    modifier = Modifier.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(food.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Pill("${96 - index * 4}%", background = TrexLime.copy(alpha = 0.16f), color = TrexLime, modifier = Modifier.padding(start = 7.dp))
                        }
                        Text(
                            "${food.nutrition.kcal} kcal · 탄수 ${food.nutrition.carb.toInt()}g · 단백질 ${food.nutrition.protein.toInt()}g · 지방 ${food.nutrition.fat.toInt()}g",
                            color = Color.White.copy(alpha = 0.64f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(TrexLime),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = TrexDark, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
    Row(
        modifier = Modifier.padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrexButton(
            text = "식단에 저장",
            onClick = onClose,
            icon = Icons.Rounded.Check,
            modifier = Modifier.weight(1f),
        )
        IconCircleButton(
            icon = Icons.Rounded.Close,
            onClick = onClose,
            size = 52.dp,
            background = TrexError.copy(alpha = 0.12f),
            contentColor = Color(0xFFFF8A8A),
            contentDescription = "닫기",
        )
    }
}

@Composable
private fun PhotoAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (primary) TrexLime else Color.White.copy(alpha = 0.06f),
        contentColor = if (primary) TrexDark else Color.White,
        border = if (primary) null else dimBorder(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (primary) TrexDark else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (primary) TrexLime else TrexLime, modifier = Modifier.size(19.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = if (primary) TrexDark.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.65f))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FoodEntryRow(food: FoodEntry, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = dimBorder(),
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(food.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${food.nutrition.kcal} kcal · 탄수 ${food.nutrition.carb.toInt()}g · 단백질 ${food.nutrition.protein.toInt()}g · 지방 ${food.nutrition.fat.toInt()}g",
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconCircleButton(
                icon = Icons.Rounded.Close,
                onClick = onRemove,
                size = 30.dp,
                background = TrexError.copy(alpha = 0.16f),
                contentColor = Color(0xFFFF8A8A),
                contentDescription = "삭제",
            )
        }
    }
}

@Composable
private fun FoodAdder(
    onAdd: (FoodEntry) -> Unit,
    onCancel: () -> Unit,
    canCancel: Boolean,
) {
    var name by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var kcal by remember { mutableStateOf("") }
    var carb by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val auto = foodDatabase[name]
    val matches = remember(name) {
        if (name.isBlank()) {
            emptyList()
        } else {
            foodDatabase.keys.filter { it.contains(name) && it != name }
        }
    }
    val ready = auto != null || (manual && name.isNotBlank() && kcal.isNotBlank())

    Surface(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel("음식 검색")
            TrexTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "예: 닭가슴살, 바나나",
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (matches.isNotEmpty() && auto == null && !manual) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matches.forEach { match ->
                        Surface(
                            onClick = { name = match },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp),
                            color = Color.White.copy(alpha = 0.06f),
                        ) {
                            Text(
                                text = "$match · ${foodDatabase.getValue(match).kcal} kcal",
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            if (auto != null) {
                Surface(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = TrexLime,
                    contentColor = TrexDark,
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text("자동 추천 영양 정보", color = TrexDark.copy(alpha = 0.68f), fontSize = 11.sp)
                        Text("${auto.kcal} kcal", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("탄수 ${auto.carb.toInt()}g · 단백질 ${auto.protein.toInt()}g · 지방 ${auto.fat.toInt()}g", color = TrexDark.copy(alpha = 0.68f), fontSize = 11.sp)
                    }
                }
            }

            if (name.isNotBlank() && auto == null && matches.isEmpty() && !manual) {
                Text(
                    text = "찾는 음식이 없나요? 직접 입력하기",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .clickable { manual = true },
                )
            }

            if (manual) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        NumberField("칼로리(kcal)", kcal, { kcal = it.decimalOnly() }, Modifier.weight(1f))
                        NumberField("탄수(g)", carb, { carb = it.decimalOnly() }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        NumberField("단백질(g)", protein, { protein = it.decimalOnly() }, Modifier.weight(1f))
                        NumberField("지방(g)", fat, { fat = it.decimalOnly() }, Modifier.weight(1f))
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrexButton(
                    text = "추가",
                    onClick = {
                        val nutrition = auto ?: Nutrition(
                            kcal = kcal.toIntOrNull() ?: 0,
                            carb = carb.toDoubleOrNull() ?: 0.0,
                            protein = protein.toDoubleOrNull() ?: 0.0,
                            fat = fat.toDoubleOrNull() ?: 0.0,
                        )
                        onAdd(FoodEntry(name, nutrition))
                    },
                    enabled = ready,
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                )
                if (canCancel) {
                    TrexButton(
                        text = "취소",
                        onClick = onCancel,
                        modifier = Modifier.width(86.dp),
                        container = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White.copy(alpha = 0.85f),
                        height = 46.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroStat(label: String, value: String, modifier: Modifier = Modifier, light: Boolean = false) {
    val bg = if (light) TrexDark.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
    val labelColor = if (light) TrexDark.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.64f)
    val valueColor = if (light) TrexDark else Color.White
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (light) Color.Transparent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = labelColor, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(TrexLime),
            modifier = Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FoodPhotoMock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF627B45), Color(0xFF27311F)))),
        contentAlignment = Alignment.Center,
    ) {
        FoodPlate()
    }
}

@Composable
private fun FoodPlate(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(210.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(138.dp)
                .clip(CircleShape)
                .background(TrexBackground),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 42.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFFD8B06B)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 42.dp)
                .size(66.dp)
                .clip(CircleShape)
                .background(Color(0xFFE6E2D0)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .width(86.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TrexGreenSoft),
        )
        Icon(Icons.Rounded.Restaurant, contentDescription = null, tint = TrexDark.copy(alpha = 0.32f), modifier = Modifier.size(42.dp))
    }
}

private fun String.decimalOnly(): String = filter { it.isDigit() || it == '.' }
