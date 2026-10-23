package com.example.trex_kotlin.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexText as Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 화면을 떠났다 돌아와도 마지막 날씨를 바로 보인다(앱 수명). */
private object WeatherUiStateCache {
    var lastRecommendation by mutableStateOf<WeatherWorkoutRecommendation?>(null)
    var lastError by mutableStateOf<String?>(null)
}

private const val WEATHER_LOAD_ERROR_MESSAGE = "날씨를 불러오지 못했어룡"

/**
 * 홈 날짜 아래의 날씨 한 줄(§108f, 사용자 요청 "기온, 날씨, 강수량만") — 하늘색 아이콘·기온·날씨·강수량. 누르면 날씨 기준 지역(또는 현재 위치)을 고른다.
 * 불러오기·캐시·화면 복귀 시 갱신·지역 바꾸기 규칙은 weather 브랜치 카드와 같다([WeatherRepository]·[resolveWeatherPlace]).
 * 판정([WeatherExerciseRecommender])은 아이콘 바탕의 하늘색(날씨·낮밤)을 고르는 데만 쓴다 — 추천 운동 목록은 홈에서 뺐다.
 */
@Composable
fun WeatherSummaryRow(services: WeatherServices, modifier: Modifier = Modifier) {
    val c = Trex.c
    val prefs = services.locationPrefs
    val repository = services.repository
    val locator = rememberWeatherLocator()
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var recommendation by remember {
        mutableStateOf(
            WeatherUiStateCache.lastRecommendation ?: run {
                // GPS 모드에서는 저장 지역 캐시를 "현재 위치"처럼 보여주면 안 된다.
                if (prefs.isGpsModeEnabled()) null else {
                    val region = prefs.getSavedRegion()
                    repository.peekCachedWeather(region.latitude, region.longitude)?.let { cached ->
                        WeatherExerciseRecommender.recommend(cached, region.displayName)
                    }
                }
            },
        )
    }
    var errorMessage by remember { mutableStateOf(WeatherUiStateCache.lastError) }
    // 잠깐 보이는 안내(브랜치의 Toast 자리 — 두 플랫폼이 같은 화면으로)
    var notice by remember { mutableStateOf<String?>(null) }
    var showRegionSheet by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    /**
     * @param forceRefresh 캐시를 건너뛰고 새로 받아온다.
     * @param locationChanged 사용자가 지역/GPS 를 바꾼 직후. 이전 지역 결과를 비워 잘못된 날씨가 남지 않게 한다.
     */
    fun loadWeather(forceRefresh: Boolean = false, locationChanged: Boolean = false) {
        // 이전 요청이 늦게 끝나 새 결과(다른 지역)를 덮어쓰지 않도록 먼저 취소한다.
        loadJob?.cancel()
        if (locationChanged) {
            recommendation = null
            WeatherUiStateCache.lastRecommendation = null
        }
        loadJob = coroutineScope.launch {
            errorMessage = null
            val place = resolveWeatherPlace(prefs, locator)
            repository.fetchCurrentWeather(place.latitude, place.longitude, forceRefresh = forceRefresh)
                .onSuccess { weather ->
                    val next = WeatherExerciseRecommender.recommend(weather, place.label)
                    recommendation = next
                    WeatherUiStateCache.lastRecommendation = next
                    WeatherUiStateCache.lastError = null
                }
                .onFailure {
                    if (recommendation == null) {
                        errorMessage = WEATHER_LOAD_ERROR_MESSAGE
                        WeatherUiStateCache.lastError = WEATHER_LOAD_ERROR_MESSAGE
                    } else if (forceRefresh) {
                        // 기존 날씨가 있으면 그대로 두되, 새로 받지 못했다는 사실은 알려준다.
                        notice = "$WEATHER_LOAD_ERROR_MESSAGE. 이전 정보를 보여드려룡."
                    }
                }
        }
    }

    fun useCurrentLocation() {
        locator.requestPermission { granted ->
            if (granted) {
                prefs.enableGpsMode()
                loadWeather(forceRefresh = true, locationChanged = true)
            } else {
                notice = "위치 권한이 없어 선택된 지역 날씨를 표시해룡."
                loadWeather(forceRefresh = true)
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            notice = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 화면 복귀 시 자동 갱신은 진행 중인 사용자 요청(지역 변경·권한 허용 직후)을 취소하지 않는다.
            if (event == Lifecycle.Event.ON_RESUME && loadJob?.isActive != true) loadWeather(forceRefresh = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rec = recommendation
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = rec != null || errorMessage != null, role = Role.Button,
                    onClickLabel = if (rec != null) "날씨 기준 지역 바꾸기" else "다시 시도") {
                    if (rec != null) showRegionSheet = true else loadWeather(forceRefresh = true)
                }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                rec != null -> {
                    val w = rec.weather
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(skyColors(rec.theme, w.isDay))),
                        contentAlignment = Alignment.Center,
                    ) { Icon(weatherIcon(w.weatherCode, w.isDay), contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                    Text("${w.temperature.roundToInt()}°", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 9.dp))
                    Text(WeatherExerciseRecommender.getWeatherDescription(w.weatherCode), color = c.text2, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                    Text("·", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 7.dp))
                    Icon(Icons.Rounded.WaterDrop, contentDescription = null, tint = RAIN_BLUE, modifier = Modifier.size(13.dp))
                    Text("강수량 ${oneDecimal(w.precipitation)}mm", color = c.text2, fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.padding(start = 3.dp))
                }
                errorMessage != null -> {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
                    Text("${errorMessage.orEmpty()} · 다시 시도", color = c.text2, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
                else -> {
                    CircularProgressIndicator(color = c.primaryText, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text("날씨를 불러오는 중", color = c.text3, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        notice?.let { Text(it, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp)) }
    }

    if (showRegionSheet) RegionSheet(
        gps = prefs.isGpsModeEnabled(),
        selected = prefs.getSavedRegion(),
        onCurrentLocation = { showRegionSheet = false; useCurrentLocation() },
        onRegion = { region ->
            prefs.saveSelectedRegion(region)
            showRegionSheet = false
            loadWeather(forceRefresh = true, locationChanged = true)
        },
        onDismiss = { showRegionSheet = false },
    )
}

/** 지역 고르기 — 현재 위치(GPS) 또는 13개 지역, 고른 것에 체크. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSheet(gps: Boolean, selected: KoreanRegion, onCurrentLocation: () -> Unit, onRegion: (KoreanRegion) -> Unit, onDismiss: () -> Unit) {
    val c = Trex.c
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("날씨 기준 지역", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
            item {
                RegionRow("현재 위치", "GPS로 지금 있는 곳의 날씨", Icons.Rounded.NearMe, checked = gps, onClick = onCurrentLocation)
                HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 24.dp))
            }
            items(KoreanRegion.entries) { region ->
                RegionRow(region.displayName, null, Icons.Rounded.LocationOn, checked = !gps && region == selected) { onRegion(region) }
            }
        }
    }
}

@Composable
private fun RegionRow(title: String, subtitle: String?, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = c.text, fontSize = 16.sp)
            subtitle?.let { Text(it, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (checked) Icon(Icons.Rounded.Check, contentDescription = "선택됨", tint = c.primaryText, modifier = Modifier.size(20.dp))
    }
}

private val RAIN_BLUE = Color(0xFF4A8FD8)

/** 아이콘 바탕 하늘색(왼쪽 위 → 오른쪽 아래) — 날씨와 낮밤. 흰 아이콘이 늘 보이도록 중간 명도까지만. */
private fun skyColors(theme: WeatherThemeMode, day: Boolean): List<Color> = when (theme) {
    WeatherThemeMode.SUNNY -> if (day) listOf(Color(0xFF2F7FD8), Color(0xFF63A9EC)) else listOf(Color(0xFF0F1D3A), Color(0xFF2A3F6A))
    WeatherThemeMode.CLOUDY -> if (day) listOf(Color(0xFF557595), Color(0xFF86A0B9)) else listOf(Color(0xFF1C2735), Color(0xFF394A5E))
    WeatherThemeMode.RAINY -> if (day) listOf(Color(0xFF3A4B60), Color(0xFF5B6E84)) else listOf(Color(0xFF151D28), Color(0xFF303C4C))
    WeatherThemeMode.SNOWY_COLD -> if (day) listOf(Color(0xFF5C87B3), Color(0xFF8DB0D3)) else listOf(Color(0xFF1E2C42), Color(0xFF41597A))
    WeatherThemeMode.HEATWAVE -> if (day) listOf(Color(0xFFD9622A), Color(0xFFEE9548)) else listOf(Color(0xFF4A2320), Color(0xFF7A3F30))
}

/** WMO 날씨 코드 → 아이콘(맑음·구름·안개·비·눈·뇌우, 맑은 밤은 달). */
private fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    2 -> if (day) Icons.Rounded.WbCloudy else Icons.Rounded.NightsStay
    3 -> Icons.Rounded.Cloud
    in 45..48 -> Icons.Rounded.Dehaze
    in 51..67, in 80..82 -> Icons.Rounded.Umbrella
    in 71..77, in 85..86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

/** 강수량 — 소수 첫째 자리까지(0 이면 "0"). */
private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${abs(tenths % 10)}"
}
