package com.example.trex_kotlin.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.trex_kotlin.TrexText as Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private object WeatherUiStateCache {
    var lastRecommendation by mutableStateOf<WeatherWorkoutRecommendation?>(null)
    var lastError by mutableStateOf<String?>(null)
}

private data class WeatherPalette(
    val background: Color,
    val card: Color,
    val text: Color,
    val text2: Color,
    val accent: Color
)

@Composable
fun WeatherRecommendationCard(
    onExerciseClick: (RecommendedExercise) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationManager = remember(context) { LocationPreferenceManager(context) }
    val repository = remember(context) { WeatherRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var recommendation by remember { mutableStateOf(WeatherUiStateCache.lastRecommendation) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf(WeatherUiStateCache.lastError) }
    var selectedRegion by remember { mutableStateOf(locationManager.getSavedRegion()) }
    var isGpsMode by remember { mutableStateOf(locationManager.useGpsMode) }
    var showRegionDialog by rememberSaveable { mutableStateOf(false) }
    var showDashboard by rememberSaveable { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    val fineLocationGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val coarseLocationGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        fineLocationGranted.value = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        coarseLocationGranted.value = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineLocationGranted.value || coarseLocationGranted.value) {
            locationManager.enableGpsMode()
            isGpsMode = true
            errorMessage = null
            WeatherUiStateCache.lastError = null
            loadWeather(forceRefresh = true, locationChanged = true)
        } else {
            isGpsMode = false
            selectedRegion = locationManager.getSavedRegion()
            errorMessage = "위치 권한이 없어 선택한 지역으로 표시해요."
            WeatherUiStateCache.lastError = errorMessage
            loadWeather(forceRefresh = true, locationChanged = true)
        }
    }

    fun loadWeather(
        forceRefresh: Boolean = false,
        locationChanged: Boolean = false
    ) {
        if (isLoading && !locationChanged && !forceRefresh) return

        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        WeatherUiStateCache.lastError = null
        if (locationChanged) {
            recommendation = null
            WeatherUiStateCache.lastRecommendation = null
        }

        loadJob = coroutineScope.launch {
            try {
                val coordinates = if (isGpsMode) {
                    locationManager.resolveCoordinates()
                } else {
                    Triple(
                        selectedRegion.latitude,
                        selectedRegion.longitude,
                        selectedRegion.displayName
                    )
                }

                val (lat, lon, locationLabel) = coordinates
                val result = repository.fetchCurrentWeather(
                    latitude = lat,
                    longitude = lon,
                    forceRefresh = forceRefresh
                )

                result.fold(
                    onSuccess = { weather ->
                        val rec = WeatherExerciseRecommender.recommend(
                            weather = weather,
                            locationLabel = locationLabel
                        )
                        recommendation = rec
                        WeatherUiStateCache.lastRecommendation = rec
                        errorMessage = null
                        WeatherUiStateCache.lastError = null
                    },
                    onFailure = { error ->
                        if (recommendation == null) {
                            errorMessage = error.message ?: "날씨 정보를 불러오지 못했어요."
                            WeatherUiStateCache.lastError = errorMessage
                        } else {
                            errorMessage = "새로고침에 실패했어요. 이전 정보를 표시해요."
                            WeatherUiStateCache.lastError = errorMessage
                        }
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "위치 정보를 확인하지 못했어요."
                WeatherUiStateCache.lastError = errorMessage
            } finally {
                isLoading = false
            }
        }
    }

    val permissionGranted = fineLocationGranted.value || coarseLocationGranted.value
    val canAutoRefreshOnResume = !isLoading && (!isGpsMode || permissionGranted)

    DisposableEffect(lifecycleOwner, isLoading, isGpsMode, permissionGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && canAutoRefreshOnResume) {
                loadWeather(forceRefresh = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { loadJob?.cancel() }
    }

    LaunchedEffect(Unit) {
        if (recommendation == null && !isLoading) {
            loadWeather(forceRefresh = false)
        }
    }

    val palette = weatherPalette(recommendation?.theme)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.card)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "날씨별 운동 추천",
                        color = palette.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            loadWeather(forceRefresh = true)
                            isRefreshing = false
                        },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "날씨 새로고침",
                            tint = palette.accent
                        )
                    }
                }

                TextButton(onClick = { showRegionDialog = true }) {
                    Text(
                        text = (if (isGpsMode) "현재 위치" else selectedRegion.displayName) + "  ▾",
                        color = palette.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(onClick = { showDashboard = !showDashboard }) {
                    Text(if (showDashboard) "대표지역 대시보드 닫기" else "대표지역 날씨 한눈에 보기")
                }

                if (showDashboard) {
                    WeatherDashboard(repository = repository)
                }

                recommendation?.let { rec ->
                    WeatherInfoPanel(
                        recommendation = rec,
                        palette = palette,
                        onExerciseClick = onExerciseClick
                    )
                }

                if (isLoading && recommendation == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = palette.accent
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = palette.text2,
                        fontSize = 12.sp
                    )
                    TextButton(
                        onClick = { loadWeather(forceRefresh = true, locationChanged = true) },
                        enabled = !isLoading
                    ) {
                        Text("다시 시도", color = palette.accent)
                    }
                }

                Text(
                    text = "날씨 정보: Open-Meteo.com",
                    color = palette.text2,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showRegionDialog) {
        AlertDialog(
            onDismissRequest = { showRegionDialog = false },
            title = { Text("날씨 기준 지역 선택") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    TextButton(
                        onClick = {
                            showRegionDialog = false
                            if (permissionGranted) {
                                locationManager.enableGpsMode()
                                isGpsMode = true
                                errorMessage = null
                                loadWeather(forceRefresh = true, locationChanged = true)
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
                        Text("현재 위치(GPS)", modifier = Modifier.fillMaxWidth())
                    }

                    HorizontalDivider()

                    KoreanRegions.representatives.forEach { representative ->
                        val expanded = showDetailRegionId == representative.id
                        TextButton(
                            onClick = {
                                if (selectedRegion.id == representative.id && !isGpsMode) {
                                    showDetailRegionId = if (expanded) null else representative.id
                                } else {
                                    selectedRegion = representative
                                    isGpsMode = false
                                    locationManager.saveSelectedRegion(representative)
                                    showDetailRegionId = representative.id
                                    loadWeather(forceRefresh = true, locationChanged = true)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (expanded) "▼ " else "▶ ") + representative.displayName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (expanded) {
                            KoreanRegions.childrenOf(representative.id).forEach { detail ->
                                TextButton(
                                    onClick = {
                                        selectedRegion = detail
                                        isGpsMode = false
                                        locationManager.saveSelectedRegion(detail)
                                        showDetailRegionId = null
                                        showRegionDialog = false
                                        loadWeather(forceRefresh = true, locationChanged = true)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp)
                                ) {
                                    Text(detail.displayName, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

private var showDetailRegionId by mutableStateOf<String?>(null)

@Composable
private fun WeatherInfoPanel(
    recommendation: WeatherWorkoutRecommendation,
    palette: WeatherPalette,
    onExerciseClick: (RecommendedExercise) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = recommendation.weatherSummary,
            color = palette.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = recommendation.precipitationSummary,
            color = palette.text2,
            fontSize = 12.sp
        )
        Text(
            text = recommendation.updateAgeText,
            color = palette.text2,
            fontSize = 11.sp
        )
        Text(
            text = recommendation.message,
            color = palette.text,
            fontSize = 13.sp
        )
        recommendation.exercises.forEach { exercise ->
            TextButton(onClick = { onExerciseClick(exercise) }) {
                Text(exercise.catalogName, color = palette.accent)
            }
        }
    }
}

@Composable
private fun weatherPalette(theme: WeatherThemeMode?): WeatherPalette {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return WeatherPalette(
        background = if (isDark) Color(0xFF101820) else Color(0xFFF6FAFF),
        card = if (isDark) Color(0xFF202B36) else Color(0xFFFFFFFF),
        text = if (isDark) Color(0xFFF3F6FA) else Color(0xFF17212B),
        text2 = if (isDark) Color(0xFFB7C3CF) else Color(0xFF667483),
        accent = when (theme) {
            WeatherThemeMode.RAINY -> Color(0xFF3B82F6)
            WeatherThemeMode.SNOWY_COLD -> Color(0xFF38BDF8)
            WeatherThemeMode.HEATWAVE -> Color(0xFFF97316)
            WeatherThemeMode.CLOUDY -> Color(0xFF64748B)
            else -> Color(0xFF16A34A)
        }
    )
}

private fun Color.luminance(): Float {
    val r = red.coerceIn(0f, 1f)
    val g = green.coerceIn(0f, 1f)
    val b = blue.coerceIn(0f, 1f)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
