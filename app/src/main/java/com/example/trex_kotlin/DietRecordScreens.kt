package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class DietRecordStep {
    Select,
    Camera,
    Analyzing,
    Result,
    Failed,
    Manual,
}

enum class DietRecordLaunchAction {
    Camera,
    Gallery,
    Recent,
    Manual,
}

private data class RecognizedFood(
    val id: Int,
    val name: String,
    val grams: Int,
    val nutrition: Nutrition,
)

@Composable
fun DietRecordRoute(
    targetGoal: Int,
    recentFoods: List<FoodEntry>,
    launchAction: DietRecordLaunchAction = DietRecordLaunchAction.Camera,
    onRecord: (slot: String, foods: List<FoodEntry>) -> Unit,
    onClose: () -> Unit,
) {
    val initialFoods = remember(launchAction, recentFoods) {
        if (launchAction == DietRecordLaunchAction.Recent) {
            recentFoods.firstOrNull()?.let { listOf(it.toRecognized(1, 100)) }.orEmpty()
        } else {
            emptyList()
        }
    }
    val initialStep = remember(launchAction, initialFoods) {
        when (launchAction) {
            DietRecordLaunchAction.Camera -> DietRecordStep.Camera
            DietRecordLaunchAction.Gallery -> DietRecordStep.Analyzing
            DietRecordLaunchAction.Recent -> if (initialFoods.isEmpty()) DietRecordStep.Manual else DietRecordStep.Result
            DietRecordLaunchAction.Manual -> DietRecordStep.Manual
        }
    }
    var step by remember { mutableStateOf(initialStep) }
    var previousStep by remember { mutableStateOf(initialStep) }
    var selectedMeal by remember { mutableStateOf(recommendedMealId()) }
    var foods by remember { mutableStateOf(initialFoods) }
    var nextFoodId by remember { mutableIntStateOf(if (initialFoods.isEmpty()) 1 else initialFoods.size + 1) }
    var selectedPhotoCount by remember { mutableIntStateOf(if (launchAction == DietRecordLaunchAction.Gallery) 3 else 1) }
    var analysisShouldFail by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var longAnalysis by remember { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableIntStateOf(1) }

    fun moveTo(next: DietRecordStep, forward: Boolean = true) {
        previousStep = step
        direction = if (forward) 1 else -1
        step = next
    }

    fun startAnalyzing(photoCount: Int, shouldFail: Boolean = false) {
        selectedPhotoCount = photoCount
        analysisShouldFail = shouldFail
        progress = 0f
        longAnalysis = false
        moveTo(DietRecordStep.Analyzing)
    }

    LaunchedEffect(step, selectedPhotoCount, analysisShouldFail) {
        if (step == DietRecordStep.Analyzing) {
            var elapsed = 0
            while (progress < 0.8f) {
                delay(260)
                elapsed += 260
                progress = (progress + 0.04f).coerceAtMost(0.8f)
                if (elapsed >= 5000) {
                    longAnalysis = true
                }
            }
            delay(500)
            progress = 1f
            delay(280)
            if (analysisShouldFail) {
                moveTo(DietRecordStep.Failed)
            } else {
                val detected = simulatedDetectedFoods(selectedPhotoCount, nextFoodId)
                foods = detected
                nextFoodId += detected.size
                moveTo(DietRecordStep.Result)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (direction >= 0) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }.using(SizeTransform(clip = false))
            },
            label = "diet-record-step",
        ) { visibleStep ->
            when (visibleStep) {
                DietRecordStep.Select -> DietRecordSelectSheet(
                    recentFoods = recentFoods,
                    onCamera = { moveTo(DietRecordStep.Camera) },
                    onGallery = { startAnalyzing(photoCount = 3) },
                    onRecent = { food ->
                        foods = listOf(food.toRecognized(nextFoodId, 100))
                        nextFoodId += 1
                        moveTo(DietRecordStep.Result)
                    },
                    onManual = { moveTo(DietRecordStep.Manual) },
                    onClose = onClose,
                )

                DietRecordStep.Camera -> DietCameraCaptureScreen(
                    onCapture = { startAnalyzing(photoCount = 1) },
                    onCancel = onClose,
                )

                DietRecordStep.Analyzing -> DietAnalyzingScreen(
                    progress = progress,
                    longAnalysis = longAnalysis,
                    onCancel = { cancelDialog = "analysis" },
                )

                DietRecordStep.Failed -> DietFailedScreen(
                    onRetry = { moveTo(DietRecordStep.Camera, forward = false) },
                    onManual = { moveTo(DietRecordStep.Manual) },
                    onClose = onClose,
                )

                DietRecordStep.Result -> DietResultScreen(
                    targetGoal = targetGoal,
                    selectedMeal = selectedMeal,
                    photoCount = selectedPhotoCount,
                    foods = foods,
                    onMealChange = { selectedMeal = it },
                    onUpdateFood = { updated ->
                        foods = foods.map { if (it.id == updated.id) updated else it }
                    },
                    onDeleteFood = { id ->
                        foods = foods.filterNot { it.id == id }
                        if (foods.isEmpty()) {
                            moveTo(DietRecordStep.Failed, forward = false)
                        }
                    },
                    onAddFood = { moveTo(DietRecordStep.Manual) },
                    onRecord = {
                        onRecord(selectedMeal, foods.map { FoodEntry(it.name, it.nutrition) })
                    },
                    onCancel = { cancelDialog = "record" },
                )

                DietRecordStep.Manual -> DietManualInputScreen(
                    hasResult = foods.isNotEmpty(),
                    onAdd = { newFood ->
                        foods = foods + newFood.copy(id = nextFoodId)
                        nextFoodId += 1
                        moveTo(DietRecordStep.Result, forward = false)
                    },
                    onCancel = {
                        if (foods.isEmpty()) {
                            onClose()
                        } else {
                            moveTo(DietRecordStep.Result, forward = false)
                        }
                    },
                )
            }
        }

        cancelDialog?.let { mode ->
            ConfirmDietDialog(
                title = if (mode == "analysis") "분석을 취소하시겠습니까?" else "기록을 취소하시겠습니까?",
                body = "현재 입력한 내용은 저장되지 않습니다.",
                confirmText = "취소하기",
                onDismiss = { cancelDialog = null },
                onConfirm = {
                    cancelDialog = null
                    onClose()
                },
            )
        }
    }
}

@Composable
private fun DietRecordSelectSheet(
    recentFoods: List<FoodEntry>,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRecent: (FoodEntry) -> Unit,
    onManual: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                color = TrexDarkAlt.copy(alpha = 0.96f),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shadowElevation = 22.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp, bottom = 18.dp),
                ) {
                    SectionLabel("사진 식단 기록", color = TrexLime)
                    ScreenTitle("기록 방식을 선택해 주세요")

                    Column(
                        modifier = Modifier.padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DietRecordActionCard(
                            title = "사진 촬영",
                            subtitle = "접시 가이드에 맞춰 바로 촬영",
                            icon = Icons.Rounded.PhotoCamera,
                            primary = true,
                            onClick = onCamera,
                        )
                        DietRecordActionCard(
                            title = "갤러리에서 선택",
                            subtitle = "여러 장 동시 선택 가능",
                            icon = Icons.Rounded.Image,
                            primary = false,
                            onClick = onGallery,
                        )
                    }

                    Text(
                        text = "최근 기록한 식단 다시 기록하기",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        recentFoods.take(3).forEach { food ->
                            Surface(
                                onClick = { onRecent(food) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.06f),
                                border = dimBorder(0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = TrexLime, modifier = Modifier.size(16.dp))
                                    Text(food.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                                    Text("${food.nutrition.kcal} kcal", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Text(
                        text = "수동으로 입력하기",
                        color = TrexLime,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clickable(onClick = onManual)
                            .padding(vertical = 9.dp),
                    )

                    DietFixedActions(
                        confirmText = null,
                        onCancel = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun DietCameraCaptureScreen(
    onCapture: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2430), Color.Black))),
    ) {
        CameraPlateGuide(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("사진 촬영", color = TrexLime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("음식을 원형 가이드 안에 맞춰 주세요", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        DietFixedActions(
            confirmText = "촬영",
            confirmIcon = Icons.Rounded.PhotoCamera,
            onConfirm = onCapture,
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DietAnalyzingScreen(
    progress: Float,
    longAnalysis: Boolean,
    onCancel: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), label = "diet-analysis-progress")
    val status = when {
        progress < 0.32f -> "사진 업로드 중..."
        progress < 0.68f -> "음식 인식 중..."
        else -> "영양 정보 계산 중..."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("분석 중", color = TrexLime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(38.dp))
            FoodPhotoPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Row(
                modifier = Modifier
                    .padding(top = 22.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = TrexLime,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
                Text("${(animatedProgress * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(status, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            if (longAnalysis) {
                Text(
                    "시간이 조금 걸리고 있어요. 잠시만 기다려주세요.",
                    color = TrexWarning,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "단백질은 체중 1kg당 1.6~2.2g 섭취가 권장됩니다.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
        DietFixedActions(
            confirmText = null,
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DietFailedScreen(
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoodPhotoPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                dim = true,
            )
            Text("음식을 찾지 못했어요", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 26.dp))
            Text(
                "사진이 너무 어둡거나 음식이 명확하지 않을 수 있습니다.",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(34.dp))
            TrexButton("다시 촬영하기", onClick = onRetry, icon = Icons.Rounded.PhotoCamera, modifier = Modifier.fillMaxWidth())
            TrexButton(
                text = "수동으로 입력하기",
                onClick = onManual,
                icon = Icons.Rounded.Edit,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth(),
                container = Color.White.copy(alpha = 0.1f),
                contentColor = Color.White,
            )
        }
        DietFixedActions(
            confirmText = null,
            onCancel = onClose,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DietResultScreen(
    targetGoal: Int,
    selectedMeal: String,
    photoCount: Int,
    foods: List<RecognizedFood>,
    onMealChange: (String) -> Unit,
    onUpdateFood: (RecognizedFood) -> Unit,
    onDeleteFood: (Int) -> Unit,
    onAddFood: () -> Unit,
    onRecord: () -> Unit,
    onCancel: () -> Unit,
) {
    val total = foods.map { FoodEntry(it.name, it.nutrition) }.totalNutrition()
    var editingId by remember { mutableStateOf<Int?>(null) }
    val partialMessage = if (photoCount > foods.size) {
        "${foods.size}개 음식을 인식했습니다. 나머지를 추가해주세요."
    } else {
        "${foods.size}개 음식을 인식했습니다."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionLabel("분석 결과", color = TrexLime)
                ScreenTitle("기록 전 확인해 주세요")
                Text(partialMessage, color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }

            item {
                MealSelector(selectedMeal = selectedMeal, onMealChange = onMealChange)
            }

            item {
                NutritionSummaryCard(total = total, targetGoal = targetGoal)
            }

            items(foods, key = { it.id }) { food ->
                RecognizedFoodCard(
                    food = food,
                    editing = editingId == food.id,
                    onEdit = { editingId = food.id },
                    onApply = {
                        onUpdateFood(it)
                        editingId = null
                    },
                    onCancelEdit = { editingId = null },
                    onDelete = { onDeleteFood(food.id) },
                )
            }

            item {
                AddFoodDashedCard(onClick = onAddFood)
            }
        }

        DietFixedActions(
            confirmText = "기록하기",
            confirmIcon = Icons.Rounded.Check,
            confirmEnabled = foods.isNotEmpty(),
            onConfirm = onRecord,
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DietManualInputScreen(
    hasResult: Boolean,
    onAdd: (RecognizedFood) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var grams by remember { mutableIntStateOf(100) }
    var manualExpanded by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualKcal by remember { mutableStateOf("") }
    var manualCarb by remember { mutableStateOf("") }
    var manualProtein by remember { mutableStateOf("") }
    var manualFat by remember { mutableStateOf("") }
    val matches = remember(query) {
        if (query.isBlank()) emptyList() else foodDatabase.keys.filter { it.contains(query.trim()) }.take(5)
    }
    val selectedNutrition = selectedName?.let { foodDatabase[it] }
    val scaledNutrition = selectedNutrition?.scaledBy(grams)
    val canAdd = scaledNutrition != null || (manualExpanded && manualName.isNotBlank() && manualKcal.isNotBlank())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionLabel(if (hasResult) "음식 추가" else "수동 입력", color = TrexLime)
                ScreenTitle("음식명을 검색해 주세요")
            }
            item {
                TrexTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedName = null
                    },
                    placeholder = "예: 닭가슴살, 바나나",
                    leadingIcon = Icons.Rounded.Search,
                )
            }
            if (matches.isNotEmpty() && selectedName == null) {
                items(matches) { name ->
                    Surface(
                        onClick = {
                            selectedName = name
                            query = name
                            manualExpanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = dimBorder(0.1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("1인분 ${foodDatabase.getValue(name).kcal} kcal", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
                        }
                    }
                }
            }

            if (selectedNutrition != null) {
                item {
                    WeightAndNutritionEditor(
                        grams = grams,
                        nutrition = scaledNutrition ?: selectedNutrition,
                        onGrams = { grams = it.coerceIn(30, 600) },
                    )
                }
            }

            item {
                Surface(
                    onClick = {
                        manualExpanded = !manualExpanded
                        selectedName = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    border = dimBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = TrexLime, modifier = Modifier.size(17.dp))
                        Text("직접 영양 정보 입력하기", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Icon(if (manualExpanded) Icons.Rounded.Close else Icons.Rounded.Add, contentDescription = null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (manualExpanded) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TrexTextField(manualName, { manualName = it }, "음식명")
                        TrexTextField(manualKcal, { manualKcal = it.digitsOnly() }, "칼로리", keyboardType = KeyboardType.Number)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrexTextField(manualCarb, { manualCarb = it.decimalOnly() }, "탄수(g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                            TrexTextField(manualProtein, { manualProtein = it.decimalOnly() }, "단백질(g)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        }
                        TrexTextField(manualFat, { manualFat = it.decimalOnly() }, "지방(g)", keyboardType = KeyboardType.Decimal)
                    }
                }
            }
        }

        DietFixedActions(
            confirmText = "추가",
            confirmIcon = Icons.Rounded.Add,
            confirmEnabled = canAdd,
            onConfirm = {
                val nutrition = scaledNutrition ?: Nutrition(
                    kcal = manualKcal.toIntOrNull() ?: 0,
                    carb = manualCarb.toDoubleOrNull() ?: 0.0,
                    protein = manualProtein.toDoubleOrNull() ?: 0.0,
                    fat = manualFat.toDoubleOrNull() ?: 0.0,
                )
                onAdd(
                    RecognizedFood(
                        id = 0,
                        name = selectedName ?: manualName.trim(),
                        grams = grams,
                        nutrition = nutrition,
                    ),
                )
            },
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
fun DietMainSummaryScreen(
    meals: List<Triple<MealMeta, List<FoodEntry>, Nutrition>>,
    total: Nutrition,
    targetGoal: Nutrition,
    recommendedGoal: Nutrition,
    dateOffset: Int,
    waterCups: Int,
    canGoPreviousDate: Boolean,
    canGoNextDate: Boolean,
    canEditSelectedDate: Boolean,
    onDateOffset: (Int) -> Unit,
    onOpenRecord: () -> Unit,
    onOpenMeal: (String, Boolean) -> Unit,
    onTargetGoalChange: (Nutrition) -> Unit,
    onWater: () -> Unit,
) {
    var goalEditorExpanded by remember { mutableStateOf(false) }
    var manualGoalMode by remember { mutableStateOf(false) }
    var draftGoal by remember(targetGoal) { mutableStateOf(targetGoal) }
    var goalFeedback by remember { mutableStateOf<String?>(null) }
    val calorieProgress = (total.kcal / targetGoal.kcal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 42.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { onDateOffset(dateOffset - 1) },
                    enabled = canGoPreviousDate,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = if (canGoPreviousDate) 0.08f else 0.035f),
                    contentColor = Color.White.copy(alpha = if (canGoPreviousDate) 1f else 0.32f),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("<", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SectionLabel("오늘의 식단", color = TrexLime)
                    ScreenTitle(if (dateOffset == 0) "오늘" else "${-dateOffset}일 전")
                }
                Surface(
                    onClick = { onDateOffset(dateOffset + 1) },
                    enabled = canGoNextDate,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = if (canGoNextDate) 0.08f else 0.035f),
                    contentColor = Color.White.copy(alpha = if (canGoNextDate) 1f else 0.32f),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(">", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = TrexDarkAlt.copy(alpha = 0.92f),
                border = dimBorder(),
            ) {
                Column(
                    modifier = Modifier
                        .animateContentSize()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CalorieGauge(progress = calorieProgress, value = total.kcal, target = targetGoal.kcal)
                    TrexButton(
                        text = "영양 목표 수정하기",
                        onClick = {
                            goalEditorExpanded = !goalEditorExpanded
                            if (goalEditorExpanded) {
                                draftGoal = targetGoal
                                goalFeedback = null
                            }
                        },
                        icon = if (goalEditorExpanded) Icons.Rounded.Close else Icons.Rounded.Edit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        container = if (goalEditorExpanded) Color.White.copy(alpha = 0.12f) else TrexLime,
                        contentColor = if (goalEditorExpanded) Color.White else TrexDark,
                        height = 46.dp,
                    )
                    NutritionGoalEditor(
                        expanded = goalEditorExpanded,
                        manualMode = manualGoalMode,
                        total = total,
                        currentGoal = targetGoal,
                        recommendedGoal = recommendedGoal,
                        draftGoal = draftGoal,
                        feedback = goalFeedback,
                        onAutoGoal = {
                            draftGoal = recommendedGoal
                            onTargetGoalChange(recommendedGoal)
                            manualGoalMode = false
                            goalFeedback = "체형별 자동 추천 목표가 적용되었습니다."
                        },
                        onManualMode = {
                            draftGoal = targetGoal
                            manualGoalMode = true
                            goalFeedback = null
                        },
                        onDraftGoalChange = {
                            draftGoal = it.normalizedGoal()
                        },
                        onApplyManual = {
                            val normalized = draftGoal.normalizedGoal()
                            draftGoal = normalized
                            onTargetGoalChange(normalized)
                            manualGoalMode = false
                            goalEditorExpanded = false
                            goalFeedback = null
                        },
                    )
                    MacroTargetBar("탄수화물", total.carb.toFloat(), targetGoal.carb.toFloat(), TrexLime)
                    MacroTargetBar("단백질", total.protein.toFloat(), targetGoal.protein.toFloat(), TrexGreenSoft)
                    MacroTargetBar("지방", total.fat.toFloat(), targetGoal.fat.toFloat(), TrexWarning)
                }
            }
        }

        items(meals, key = { it.first.id }) { (meta, foods, nutrition) ->
            DietMealSection(
                meta = meta,
                foods = foods,
                total = nutrition,
                onAdd = onOpenRecord,
                onOpen = { onOpenMeal(meta.id, foods.isEmpty()) },
            )
        }

        item {
            WaterTracker(cups = waterCups, onAdd = onWater)
        }
    }
}

@Composable
fun DietUndoToast(
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF11151C).copy(alpha = 0.96f),
        contentColor = Color.White,
        border = dimBorder(0.14f),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = TrexLime, modifier = Modifier.size(17.dp))
            Text("식단이 기록되었습니다", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(
                text = "실행 취소",
                color = TrexLime,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun MealSelector(selectedMeal: String, onMealChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        mealMetas.forEach { meta ->
            val active = selectedMeal == meta.id
            Surface(
                onClick = { onMealChange(meta.id) },
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (active) TrexLime else Color.White.copy(alpha = 0.07f),
                contentColor = if (active) TrexDark else Color.White.copy(alpha = 0.76f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(meta.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NutritionSummaryCard(total: Nutrition, targetGoal: Int) {
    val pct = (total.kcal / targetGoal.toFloat()).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = TrexLime,
        contentColor = TrexDark,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("해당 끼니 합산", color = TrexDark.copy(alpha = 0.68f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Pill("하루 목표의 ${(pct * 100).toInt()}%", background = TrexDark, color = TrexLime)
            }
            Text("${total.kcal} kcal", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroMini("탄수", "${total.carb.toInt()}g", Modifier.weight(1f))
                MacroMini("단백질", "${total.protein.toInt()}g", Modifier.weight(1f))
                MacroMini("지방", "${total.fat.toInt()}g", Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognizedFoodCard(
    food: RecognizedFood,
    editing: Boolean,
    onEdit: () -> Unit,
    onApply: (RecognizedFood) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(food.id, editing) { mutableStateOf(food.name) }
    var grams by remember(food.id, editing) { mutableStateOf(food.grams.toString()) }
    var kcal by remember(food.id, editing) { mutableStateOf(food.nutrition.kcal.toString()) }
    var carb by remember(food.id, editing) { mutableStateOf(food.nutrition.carb.toInt().toString()) }
    var protein by remember(food.id, editing) { mutableStateOf(food.nutrition.protein.toInt().toString()) }
    var fat by remember(food.id, editing) { mutableStateOf(food.nutrition.fat.toInt().toString()) }
    val matches = remember(name) {
        if (name.isBlank() || foodDatabase.containsKey(name)) emptyList() else foodDatabase.keys.filter { it.contains(name) }.take(3)
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(TrexError.copy(alpha = 0.22f))
                    .padding(end = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF8A8A), modifier = Modifier.size(22.dp))
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = dimBorder(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(food.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${food.grams}g · ${food.nutrition.kcal} kcal · 탄수 ${food.nutrition.carb.toInt()}g · 단백질 ${food.nutrition.protein.toInt()}g · 지방 ${food.nutrition.fat.toInt()}g",
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IconCircleButton(Icons.Rounded.Edit, onClick = onEdit, size = 34.dp, background = Color.White.copy(alpha = 0.09f), contentDescription = "수정")
                }

                AnimatedVisibility(visible = editing, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        TrexTextField(name, { name = it }, "음식명", leadingIcon = Icons.Rounded.Search)
                        matches.forEach { match ->
                            Surface(
                                onClick = {
                                    name = match
                                    val scaled = foodDatabase.getValue(match).scaledBy(grams.toIntOrNull() ?: 100)
                                    kcal = scaled.kcal.toString()
                                    carb = scaled.carb.toInt().toString()
                                    protein = scaled.protein.toInt().toString()
                                    fat = scaled.fat.toInt().toString()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("$match · ${foodDatabase.getValue(match).kcal} kcal", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                            }
                        }
                        TrexTextField(grams, { grams = it.digitsOnly().take(4) }, "중량(g)", keyboardType = KeyboardType.Number)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrexTextField(kcal, { kcal = it.digitsOnly() }, "kcal", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                            TrexTextField(carb, { carb = it.decimalOnly() }, "탄수", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrexTextField(protein, { protein = it.decimalOnly() }, "단백질", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                            TrexTextField(fat, { fat = it.decimalOnly() }, "지방", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrexButton(
                                text = "적용",
                                onClick = {
                                    onApply(
                                        food.copy(
                                            name = name.ifBlank { food.name },
                                            grams = grams.toIntOrNull() ?: food.grams,
                                            nutrition = Nutrition(
                                                kcal = kcal.toIntOrNull() ?: food.nutrition.kcal,
                                                carb = carb.toDoubleOrNull() ?: food.nutrition.carb,
                                                protein = protein.toDoubleOrNull() ?: food.nutrition.protein,
                                                fat = fat.toDoubleOrNull() ?: food.nutrition.fat,
                                            ),
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                            )
                            TrexButton(
                                text = "취소",
                                onClick = onCancelEdit,
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                                container = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddFoodDashedCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.035f),
        border = BorderStroke(1.dp, TrexLime.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = TrexLime, modifier = Modifier.size(18.dp))
            Text("음식 추가하기", color = TrexLime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun WeightAndNutritionEditor(
    grams: Int,
    nutrition: Nutrition,
    onGrams: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("중량", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(Modifier.width(96.dp)) {
                    TrexTextField(grams.toString(), { it.toIntOrNull()?.let(onGrams) }, "g", keyboardType = KeyboardType.Number)
                }
            }
            Slider(
                value = grams.toFloat(),
                onValueChange = { onGrams(it.toInt()) },
                valueRange = 30f..600f,
                colors = SliderDefaults.colors(
                    thumbColor = TrexLime,
                    activeTrackColor = TrexLime,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                ),
            )
            Text("${nutrition.kcal} kcal · 탄수 ${nutrition.carb.toInt()}g · 단백질 ${nutrition.protein.toInt()}g · 지방 ${nutrition.fat.toInt()}g", color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DietRecordActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
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
                Icon(icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = if (primary) TrexDark.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun DietFixedActions(
    confirmText: String?,
    modifier: Modifier = Modifier,
    confirmIcon: ImageVector = Icons.Rounded.Check,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, TrexDark.copy(alpha = 0.96f))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (confirmText != null) {
            TrexButton(
                text = confirmText,
                onClick = onConfirm,
                enabled = confirmEnabled,
                icon = confirmIcon,
                modifier = Modifier.weight(1f),
                height = 56.dp,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconCircleButton(
            icon = Icons.Rounded.Close,
            onClick = onCancel,
            size = 56.dp,
            background = TrexError.copy(alpha = 0.16f),
            contentColor = Color(0xFFFF8A8A),
            contentDescription = "닫기",
        )
    }
}

@Composable
private fun ConfirmDietDialog(
    title: String,
    body: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = TrexDarkAlt,
            contentColor = Color.White,
            border = dimBorder(0.14f),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrexButton(confirmText, onClick = onConfirm, modifier = Modifier.weight(1f), container = TrexError.copy(alpha = 0.18f), contentColor = Color(0xFFFF8A8A))
                    TrexButton("계속하기", onClick = onDismiss, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FoodPhotoPreview(
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF384B35), Color(0xFF171C14))))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(26.dp)),
    ) {
        FoodPlateArt(modifier = Modifier.align(Alignment.Center).size(190.dp))
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)))
    }
}

@Composable
private fun CameraPlateGuide(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF263348), Color(0xFF0B0D12))))
        val radius = size.minDimension * 0.34f
        drawCircle(
            color = TrexLime.copy(alpha = 0.72f),
            radius = radius,
            center = Offset(size.width / 2f, size.height * 0.50f),
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = radius * 0.92f,
            center = Offset(size.width / 2f, size.height * 0.50f),
        )
    }
}

@Composable
private fun FoodPlateArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color.White.copy(alpha = 0.9f), radius = size.minDimension * 0.46f)
        drawCircle(Color(0xFFE8EDE0), radius = size.minDimension * 0.36f)
        drawCircle(TrexGreen.copy(alpha = 0.82f), radius = size.minDimension * 0.13f, center = Offset(size.width * 0.38f, size.height * 0.42f))
        drawCircle(TrexWarning.copy(alpha = 0.84f), radius = size.minDimension * 0.12f, center = Offset(size.width * 0.62f, size.height * 0.40f))
        drawRoundRect(
            color = Color(0xFFEAD2A5),
            topLeft = Offset(size.width * 0.34f, size.height * 0.58f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
        )
    }
}

@Composable
private fun NutritionGoalEditor(
    expanded: Boolean,
    manualMode: Boolean,
    total: Nutrition,
    currentGoal: Nutrition,
    recommendedGoal: Nutrition,
    draftGoal: Nutrition,
    feedback: String?,
    onAutoGoal: () -> Unit,
    onManualMode: () -> Unit,
    onDraftGoalChange: (Nutrition) -> Unit,
    onApplyManual: () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrexButton(
                    text = "체형별 자동 추천",
                    onClick = onAutoGoal,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.AutoAwesome,
                    height = 46.dp,
                )
                TrexButton(
                    text = "수동 입력",
                    onClick = onManualMode,
                    modifier = Modifier.weight(1f),
                    container = if (manualMode) TrexLime else Color.White.copy(alpha = 0.1f),
                    contentColor = if (manualMode) TrexDark else Color.White,
                    height = 46.dp,
                )
            }

            Text(
                text = "현재 목표 ${currentGoal.kcal} kcal · 탄수 ${currentGoal.carb.toInt()}g · 단백질 ${currentGoal.protein.toInt()}g · 지방 ${currentGoal.fat.toInt()}g",
                color = Color.White.copy(alpha = 0.64f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                text = "자동 추천 ${recommendedGoal.kcal} kcal · 탄수 ${recommendedGoal.carb.toInt()}g · 단백질 ${recommendedGoal.protein.toInt()}g · 지방 ${recommendedGoal.fat.toInt()}g",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (feedback != null) {
                Pill(
                    text = feedback,
                    background = TrexLime.copy(alpha = 0.16f),
                    color = TrexLime,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnimatedVisibility(
                visible = manualMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ManualNutritionGoalEditor(
                    total = total,
                    draftGoal = draftGoal,
                    onDraftGoalChange = onDraftGoalChange,
                    onApplyManual = onApplyManual,
                )
            }
        }
    }
}

@Composable
private fun ManualNutritionGoalEditor(
    total: Nutrition,
    draftGoal: Nutrition,
    onDraftGoalChange: (Nutrition) -> Unit,
    onApplyManual: () -> Unit,
) {
    val introProgress = remember { Animatable(0f) }
    var slidersReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        slidersReady = false
        introProgress.snapTo(0f)
        introProgress.animateTo(1f, animationSpec = tween(durationMillis = 720))
        slidersReady = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GoalSlider(
            label = "칼로리",
            currentValue = total.kcal.toFloat(),
            targetValue = draftGoal.kcal.toFloat(),
            minValue = 1000f,
            maxValue = 3600f,
            unit = "kcal",
            color = TrexLime,
            enabled = slidersReady,
            introProgress = if (slidersReady) null else introProgress.value,
            wholeNumber = true,
            onTargetValueChange = { onDraftGoalChange(draftGoal.copy(kcal = it.roundToInt())) },
        )
        GoalSlider(
            label = "탄수화물",
            currentValue = total.carb.toFloat(),
            targetValue = draftGoal.carb.toFloat(),
            minValue = 60f,
            maxValue = 480f,
            unit = "g",
            color = TrexLime,
            enabled = slidersReady,
            introProgress = if (slidersReady) null else introProgress.value,
            onTargetValueChange = { onDraftGoalChange(draftGoal.copy(carb = it.toDouble())) },
        )
        GoalSlider(
            label = "단백질",
            currentValue = total.protein.toFloat(),
            targetValue = draftGoal.protein.toFloat(),
            minValue = 40f,
            maxValue = 240f,
            unit = "g",
            color = TrexGreenSoft,
            enabled = slidersReady,
            introProgress = if (slidersReady) null else introProgress.value,
            onTargetValueChange = { onDraftGoalChange(draftGoal.copy(protein = it.toDouble())) },
        )
        GoalSlider(
            label = "지방",
            currentValue = total.fat.toFloat(),
            targetValue = draftGoal.fat.toFloat(),
            minValue = 20f,
            maxValue = 160f,
            unit = "g",
            color = TrexWarning,
            enabled = slidersReady,
            introProgress = if (slidersReady) null else introProgress.value,
            onTargetValueChange = { onDraftGoalChange(draftGoal.copy(fat = it.toDouble())) },
        )
        TrexButton(
            text = "완료하기",
            onClick = onApplyManual,
            enabled = slidersReady,
            icon = Icons.Rounded.Check,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
    }
}

@Composable
private fun GoalSlider(
    label: String,
    currentValue: Float,
    targetValue: Float,
    minValue: Float,
    maxValue: Float,
    unit: String,
    color: Color,
    enabled: Boolean,
    introProgress: Float?,
    wholeNumber: Boolean = false,
    onTargetValueChange: (Float) -> Unit,
) {
    val safeTarget = targetValue.coerceIn(minValue, maxValue)
    val statusProgress = introProgress ?: (currentValue / safeTarget.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = statusProgress,
        animationSpec = tween(durationMillis = if (introProgress == null) 180 else 720),
        label = "$label-goal-progress",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "현재 ${formatGoalValue(currentValue, wholeNumber)}$unit / 목표 ${formatGoalValue(safeTarget, wholeNumber)}$unit",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                )
            }
            GoalValueInput(
                value = safeTarget,
                unit = unit,
                minValue = minValue,
                maxValue = maxValue,
                wholeNumber = wholeNumber,
                onValueChange = onTargetValueChange,
            )
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.12f),
        )
        Slider(
            value = safeTarget,
            onValueChange = { onTargetValueChange(it.coerceIn(minValue, maxValue)) },
            enabled = enabled,
            valueRange = minValue..maxValue,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                disabledThumbColor = color.copy(alpha = 0.7f),
                disabledActiveTrackColor = color.copy(alpha = 0.7f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
            ),
        )
    }
}

@Composable
private fun GoalValueInput(
    value: Float,
    unit: String,
    minValue: Float,
    maxValue: Float,
    wholeNumber: Boolean,
    onValueChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatGoalValue(value, wholeNumber)) }

    LaunchedEffect(value, editing) {
        if (!editing) {
            text = formatGoalValue(value, wholeNumber)
        }
    }

    if (editing) {
        Box(Modifier.width(112.dp)) {
            TrexTextField(
                value = text,
                onValueChange = { raw ->
                    val clean = if (wholeNumber) raw.digitsOnly() else raw.decimalOnly()
                    text = clean
                    clean.toFloatOrNull()?.let { onValueChange(it.coerceIn(minValue, maxValue)) }
                },
                placeholder = unit,
                keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal,
            )
        }
    } else {
        Surface(
            onClick = {
                text = formatGoalValue(value, wholeNumber)
                editing = true
            },
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
        ) {
            Text(
                text = "${formatGoalValue(value, wholeNumber)}$unit",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CalorieGauge(progress: Float, value: Int, target: Int) {
    Box(modifier = Modifier.size(172.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension / 2f - stroke.width, style = stroke)
            drawArc(TrexLime, startAngle = -90f, sweepAngle = progress * 360f, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text("/ $target kcal", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MacroTargetBar(label: String, value: Float, target: Float, color: Color) {
    val progress = (value / target).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row {
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${value.toInt()} / ${target.toInt()}g", color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun DietMealSection(
    meta: MealMeta,
    foods: List<FoodEntry>,
    total: Nutrition,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meta.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${total.kcal} kcal", color = TrexLime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (foods.isEmpty()) {
                TrexButton(
                    text = "기록 추가하기",
                    onClick = onAdd,
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    container = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White,
                    height = 46.dp,
                )
            } else {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    foods.forEach { food ->
                        Surface(
                            onClick = onOpen,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(15.dp),
                            color = Color.White.copy(alpha = 0.06f),
                        ) {
                            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(food.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${food.nutrition.kcal} kcal", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaterTracker(cups: Int, onAdd: () -> Unit) {
    Surface(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = dimBorder(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF7EC8FF).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("컵", color = Color(0xFF9ED5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("물 섭취량", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("컵 아이콘을 터치하면 1잔씩 추가돼요", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
            }
            Text("${cups}잔", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MacroMini(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(15.dp),
        color = TrexDark.copy(alpha = 0.12f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center) {
            Text(label, color = TrexDark.copy(alpha = 0.62f), fontSize = 10.sp)
            Text(value, color = TrexDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun simulatedDetectedFoods(photoCount: Int, firstId: Int): List<RecognizedFood> {
    val base = listOf(
        FoodEntry("현미밥", Nutrition(220, 46.0, 5.0, 1.7)),
        FoodEntry("닭가슴살", Nutrition(165, 0.0, 31.0, 3.6)),
        FoodEntry("샐러드", Nutrition(120, 8.0, 4.0, 7.0)),
    )
    return base.take(if (photoCount > 1) 1 else 3).mapIndexed { index, food ->
        food.toRecognized(firstId + index, if (food.name == "현미밥") 150 else 100)
    }
}

private fun FoodEntry.toRecognized(id: Int, grams: Int): RecognizedFood =
    RecognizedFood(id = id, name = name, grams = grams, nutrition = nutrition)

private fun Nutrition.scaledBy(grams: Int): Nutrition {
    val scale = grams / 100.0
    return Nutrition(
        kcal = (kcal * scale).toInt(),
        carb = carb * scale,
        protein = protein * scale,
        fat = fat * scale,
    )
}

private fun recommendedMealId(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 10 -> "breakfast"
        hour < 14 -> "lunch"
        hour < 18 -> "snack"
        else -> "dinner"
    }
}

private fun Nutrition.normalizedGoal(): Nutrition = Nutrition(
    kcal = kcal.coerceIn(800, 5000),
    carb = carb.coerceIn(0.0, 800.0),
    protein = protein.coerceIn(0.0, 400.0),
    fat = fat.coerceIn(0.0, 250.0),
)

private fun formatGoalValue(value: Float, wholeNumber: Boolean): String =
    if (wholeNumber) {
        value.roundToInt().toString()
    } else {
        value.roundToInt().toString()
    }

private fun String.digitsOnly(): String = filter(Char::isDigit)

private fun String.decimalOnly(): String = filter { it.isDigit() || it == '.' }

