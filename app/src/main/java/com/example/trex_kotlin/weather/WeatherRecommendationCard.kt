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
    var showRegionDialog by remember { mutableStateOf(false) }

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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, c.line)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            if (isLoading && recommendation == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = c.primary
                    )
                }
            } else if (errorMessage != null && recommendation == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = c.text2,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { loadWeather(forceRefresh = true) }) {
                        Text(
                            text = "다시 시도",
                            color = c.primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                recommendation?.let { rec ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(c.primaryWash)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = rec.theme.emoji + " " + rec.theme.titleKorean + " · " + rec.environment.labelKorean,
                                    color = c.primaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        TextButton(
                            onClick = { showRegionDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "📍 " + rec.locationLabel + " ▾",
                                color = c.text2,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rec.weatherSummary,
                                color = c.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = rec.updateAgeText,
                                color = c.text2,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        TextButton(
                            onClick = { loadWeather(forceRefresh = true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "↻ 새로고침",
                                color = c.text2,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = rec.message,
                        color = c.text2,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    rec.exercises.forEach { exercise ->
                        val isAdded = exercise.catalogName in plannedExerciseNames
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !isAdded) { onExerciseClick(exercise) },
                            shape = RoundedCornerShape(16.dp),
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
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = exercise.description,
                                        color = c.text2,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = if (isAdded) "목록에 있음" else "운동 목록에 추가",
                                    color = if (isAdded) c.text2 else c.primaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "날씨 정보: Open-Meteo.com",
                        color = c.text2,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showRegionDialog) {
        AlertDialog(
            onDismissRequest = { showRegionDialog = false },
            containerColor = c.surface,
            titleContentColor = c.text,
            textContentColor = c.text,
            title = { Text("날씨 기준 지역 선택", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            showRegionDialog = false
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
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🎯 현재 위치(GPS)",
                            color = c.primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    HorizontalDivider(color = c.line)
                    KoreanRegion.entries.forEach { region ->
                        TextButton(
                            onClick = {
                                preferenceManager.saveSelectedRegion(region)
                                showRegionDialog = false
                                loadWeather(forceRefresh = true)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📍 " + region.displayName,
                                color = c.text,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDialog = false }) {
                    Text("닫기", color = c.text2, fontSize = 13.sp)
                }
            }
        )
    }
}
