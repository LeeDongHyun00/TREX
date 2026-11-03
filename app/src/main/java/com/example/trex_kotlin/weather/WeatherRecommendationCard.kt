package com.example.trex_kotlin.weather

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexText as Text
import kotlinx.coroutines.launch

private object WeatherUiStateCache {
    var lastRecommendation by mutableStateOf<WeatherWorkoutRecommendation?>(null)
    var lastError by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherRecommendationCard(
    plannedExerciseNames: Set<String> = emptySet(),
    onExerciseClick: (RecommendedExercise) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = Trex.c
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferenceManager = remember { LocationPreferenceManager(context) }
    val repository = remember { WeatherRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var recommendation by remember {
        mutableStateOf(
            WeatherUiStateCache.lastRecommendation ?: run {
                val region = preferenceManager.getSavedRegion()
                repository.peekCachedWeather(region.latitude, region.longitude)?.let { cached ->
                    WeatherExerciseRecommender.recommend(cached, region.displayName)
                }
            }
        )
    }
    var isLoading by remember { mutableStateOf(recommendation == null && WeatherUiStateCache.lastError == null) }
    var errorMessage by remember { mutableStateOf(WeatherUiStateCache.lastError) }
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun loadWeather(forceRefresh: Boolean = false) {
        coroutineScope.launch {
            if (recommendation == null) {
                isLoading = true
            }
            errorMessage = null
            val coords = preferenceManager.resolveCoordinates()
            repository.fetchCurrentWeather(coords.first, coords.second, forceRefresh = forceRefresh)
                .onSuccess { weather ->
                    val next = WeatherExerciseRecommender.recommend(weather, coords.third)
                    recommendation = next
                    WeatherUiStateCache.lastRecommendation = next
                    WeatherUiStateCache.lastError = null
                    isLoading = false
                }
                .onFailure {
                    if (recommendation == null || forceRefresh) {
                        val msg = "날씨 정보를 불러오지 못했어룡."
                        errorMessage = msg
                        WeatherUiStateCache.lastError = msg
                    }
                    isLoading = false
                }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            preferenceManager.enableGpsMode()
        } else {
            Toast.makeText(context, "위치 권한이 거부되어 선택된 지역 날씨를 표시해룡.", Toast.LENGTH_SHORT).show()
        }
        loadWeather(forceRefresh = true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                loadWeather(forceRefresh = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 1. 상단 컴팩트 한 줄 바 UI
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showSheet = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val currentRec = recommendation
        if (currentRec != null) {
            Text(
                text = currentRec.theme.emoji,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = currentRec.locationLabel + " " + currentRec.weatherSummary,
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = " >",
                color = c.text2,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = c.primary)
            Text(
                text = " 날씨 불러오는 중...",
                color = c.text2,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        } else {
            Text(
                text = "날씨 정보 갱신 실패 (탭하여 재시도)",
                color = c.text2,
                fontSize = 12.sp
            )
        }
    }

    // 2. 탭 시 나타나는 상세 바텀시트 모달
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = c.surface,
            contentColor = c.text
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                recommendation?.let { rec ->
                    // 바텀시트 헤더: 테마 & 권장 환경
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rec.theme.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = rec.theme.titleKorean,
                                    color = c.text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = rec.locationLabel + " · " + rec.weatherSummary + " · " + rec.environment.labelKorean,
                                    color = c.text2,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        TextButton(onClick = { loadWeather(forceRefresh = true) }) {
                            Text("↻ 새로고침", color = c.primaryText, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = rec.message,
                        color = c.text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "추천 운동",
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    rec.exercises.forEach { exercise ->
                        val isAdded = exercise.catalogName in plannedExerciseNames
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !isAdded) { onExerciseClick(exercise) },
                            shape = RoundedCornerShape(12.dp),
                            color = c.surface2,
                            border = BorderStroke(1.dp, c.line)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = exercise.title,
                                        color = c.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = exercise.description,
                                        color = c.text2,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = if (isAdded) "목록에 있음" else "목록에 추가",
                                    color = if (isAdded) c.text2 else c.primaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = c.line)
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "날씨 기준 지역",
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    // GPS 현재 위치 옵션
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (preferenceManager.hasLocationPermission()) {
                                    preferenceManager.enableGpsMode()
                                    loadWeather(forceRefresh = true)
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎯", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("현재 위치", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("GPS로 지금 있는 곳의 날씨", color = c.text2, fontSize = 11.sp)
                        }
                        if (preferenceManager.isGpsModeEnabled()) {
                            Text("✓", color = c.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // 지역 선택 목록
                    KoreanRegion.entries.forEach { region ->
                        val isSelected = !preferenceManager.isGpsModeEnabled() && preferenceManager.getSavedRegion() == region
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    preferenceManager.saveSelectedRegion(region)
                                    loadWeather(forceRefresh = true)
                                }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📍", fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = region.displayName,
                                color = c.text,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Text("✓", color = c.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}