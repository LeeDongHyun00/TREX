package com.example.trex_kotlin.weather

import com.example.trex_kotlin.SheetHost

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.nowMillis
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
 * 홈 날짜 아래의 날씨 한 줄(§108f, 사용자 요청 "기온, 날씨, 강수량만") — 하늘색 아이콘·기온·날씨·강수량. 누르면 날씨 시트가 열린다.
 * 불러오기·캐시·화면 복귀 시 갱신·지역 바꾸기 규칙은 weather 브랜치 카드와 같다([WeatherRepository]·[resolveWeatherPlace]).
 * 판정([WeatherExerciseRecommender])은 아이콘 바탕의 하늘색(날씨·낮밤)을 고른다. 추천 운동 목록은 홈 한 화면을 지키려고 줄에서 빼고
 * **시트 위쪽**에 둔다(날씨 시트 — 추천·지역·출처, 스펙 '날씨 기반 운동 추천 판정 기준'의 화면 절).
 * [isInPlan]·[onAddExercise] 는 추천 운동을 운동 목록에 넣는 길이다 — 근육 부하 화면 추천과 같은 규칙(오늘 안 한 같은 이름이 있으면 '목록에 있음').
 * [onAddExercise] 가 null 이면 추천은 보이기만 한다.
 */
@Composable
fun WeatherSummaryRow(
    services: WeatherServices,
    modifier: Modifier = Modifier,
    isInPlan: (String) -> Boolean = { false },
    onAddExercise: ((String) -> Unit)? = null,
) {
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
    var showWeatherSheet by remember { mutableStateOf(false) }
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
            // 시트는 추천이 있을 때만 그린다 — 열린 표시를 남겨 두면 다음 날씨가 오는 순간 저절로 다시 열린다
            showWeatherSheet = false
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
                    onClickLabel = if (rec != null) "날씨 운동 추천과 기준 지역 보기" else "다시 시도") {
                    if (rec != null) showWeatherSheet = true else loadWeather(forceRefresh = true)
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
                    // 좁은 화면·큰 글씨에서는 날씨 이름이 먼저 줄어든다(weight) — 강수량과 › 가 밀려나지 않게
                    Text(WeatherExerciseRecommender.getWeatherDescription(w.weatherCode), color = c.text2, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 7.dp))
                    Text("·", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 7.dp))
                    Icon(Icons.Rounded.WaterDrop, contentDescription = null, tint = RAIN_BLUE, modifier = Modifier.size(13.dp))
                    Text("강수량 ${oneDecimal(w.precipitation)}mm", color = c.text2, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp))
                    // 누르면 추천이 열린다는 표시 — 줄 모양(한 줄)은 그대로
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.text3,
                        modifier = Modifier.padding(start = 2.dp).size(18.dp))
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

    if (showWeatherSheet && rec != null) WeatherSheet(
        recommendation = rec,
        gps = prefs.isGpsModeEnabled(),
        selected = prefs.getSavedRegion(),
        isInPlan = isInPlan,
        onAddExercise = onAddExercise,
        onCurrentLocation = { showWeatherSheet = false; useCurrentLocation() },
        onRegion = { region ->
            prefs.saveSelectedRegion(region)
            showWeatherSheet = false
            loadWeather(forceRefresh = true, locationChanged = true)
        },
        onDismiss = { showWeatherSheet = false },
    )
}

/**
 * 날씨 시트 — 위에서부터 오늘의 날씨 운동 추천(판정 [WeatherExerciseRecommender], weather 브랜치 카드의 내용),
 * 출처(Open-Meteo, CC BY 4.0 — 홈 한 줄에는 자리가 없어 시트를 열면 바로 보이는 추천 아래에 단다, 누르면 Open-Meteo 사이트)와 받은 지 얼마나 됐는지,
 * 날씨 기준 지역(현재 위치 또는 13개 지역, 고른 것에 체크).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherSheet(
    recommendation: WeatherWorkoutRecommendation,
    gps: Boolean,
    selected: KoreanRegion,
    isInPlan: (String) -> Boolean,
    onAddExercise: ((String) -> Unit)?,
    onCurrentLocation: () -> Unit,
    onRegion: (KoreanRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Trex.c
    val uri = LocalUriHandler.current
    // 추천을 만든 순간의 글이 아니라 지금 기준 — 새로 받기에 실패해 남은 묵은 날씨를 '방금' 이라 하지 않는다
    val age = WeatherExerciseRecommender.updateAgeText(recommendation.weather.fetchedAtMillis, nowMillis())
    SheetHost(onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item { RecommendationHeader(recommendation) }
            items(recommendation.exercises) { exercise ->
                RecommendedExerciseRow(exercise, inPlan = isInPlan(exercise.catalogName), onAdd = onAddExercise)
            }
            item {
                Text("날씨 정보: Open-Meteo.com (CC BY 4.0) · $age", color = c.text3, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open-Meteo 사이트 열기") { uri.openUri("https://open-meteo.com/") }
                        .padding(horizontal = 24.dp, vertical = 8.dp))
                HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp))
                Text("날씨 기준 지역", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 4.dp))
                RegionRow("현재 위치", "GPS로 지금 있는 곳의 날씨", Icons.Rounded.NearMe, checked = gps, onClick = onCurrentLocation)
                HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 24.dp))
            }
            items(KoreanRegion.entries) { region ->
                RegionRow(region.displayName, null, Icons.Rounded.LocationOn, checked = !gps && region == selected) { onRegion(region) }
            }
        }
    }
}

/** 시트 맨 위 — 하늘색 아이콘·추천 제목(날씨 테마·실내/실외)·지역과 기온·추천 문장. */
@Composable
private fun RecommendationHeader(rec: WeatherWorkoutRecommendation) {
    val c = Trex.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(skyColors(rec.theme, rec.weather.isDay))),
                contentAlignment = Alignment.Center,
            ) { Icon(weatherIcon(rec.weather.weatherCode, rec.weather.isDay), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(rec.theme.titleKorean, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${rec.weatherSummary} · ${rec.environment.labelKorean}", color = c.text2, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(rec.message, color = c.text2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    }
}

/** 추천 운동 한 줄 — 이름·이유, 오른쪽에 '목록에 추가'(이미 있으면 '목록에 있음', 누를 수 없음). */
@Composable
private fun RecommendedExerciseRow(exercise: RecommendedExercise, inPlan: Boolean, onAdd: ((String) -> Unit)?) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(exercise.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(exercise.description, color = c.text2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onAdd != null) {
            OutlinedButton(
                onClick = { onAdd(exercise.catalogName) },
                enabled = !inPlan,
                contentPadding = PaddingValues(horizontal = 12.dp),
                // 화면 낭독기가 세 버튼을 가를 수 있게 운동 이름을 붙인다
                modifier = Modifier.padding(start = 12.dp).heightIn(min = 36.dp)
                    .semantics { contentDescription = exercise.title + if (inPlan) " 운동 목록에 있음" else " 운동 목록에 추가" },
            ) {
                Text(if (inPlan) "목록에 있음" else "목록에 추가", color = if (inPlan) c.text2 else c.primaryText, fontSize = 13.sp, maxLines = 1)
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
