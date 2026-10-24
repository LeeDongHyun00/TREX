package com.example.trex_kotlin.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.MuscleLoadDatabase
import com.example.trex_kotlin.ThemeMode
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.TrexAppTheme
import com.example.trex_kotlin.TrexStore
import com.example.trex_kotlin.currentHour
import com.example.trex_kotlin.isoUtcSeconds
import com.example.trex_kotlin.koreanDayOfWeek
import com.example.trex_kotlin.localToday
import com.example.trex_kotlin.monthDayLabel
import com.example.trex_kotlin.nameUuidFromBytes
import com.example.trex_kotlin.newUuid
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.posture.SetLabelFormat
import com.example.trex_kotlin.posture.SetSelfLabel
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import com.example.trex_kotlin.sync.TrexApi
import com.example.trex_kotlin.todayEpochDay
import com.example.trex_kotlin.todayPlan
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController
import com.example.trex_kotlin.TrexText as Text

/**
 * 개발용 실기기 확인(실행 인자 --app-probe, §108 Phase 2): 공유 앱 상태(AppViewModel)가 iPhone 플랫폼 구현 위에서 실제로 도는가.
 * 기록 저장소는 건드리지 않는다 — 키-값은 `probe.` 이름공간, 파일은 Documents/app-probe 아래. 결과는 화면과 Documents/app-probe.txt.
 */
fun AppProbeViewController(): UIViewController = ComposeUIViewController {
    val lines = remember { mutableStateListOf("확인 중…") }
    LaunchedEffect(Unit) {
        val result = runAppProbe()
        lines.clear(); lines.addAll(result)
        writeTextFile(documentsPath() + "/app-probe.txt", result.joinToString("\n") + "\n")
    }
    TrexAppTheme(ThemeMode.Light) {
        Column(
            Modifier.fillMaxSize().background(Trex.c.bg).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEach { Text(it, color = if (it.startsWith("FAIL")) Trex.c.err else Trex.c.text, fontSize = 13.sp) }
        }
    }
}

private class ProbePlatform(private val root: String) : IosAppPlatform() {
    override fun preferences(name: String): KeyValueStore = UserDefaultsStore("probe.$name")
    override fun muscleLoadDatabase(): MuscleLoadDatabase = FileMuscleLoadDatabase("$root/muscle_load.json")
    override fun appendSetLabel(label: SetSelfLabel) = SetLabelFiles("$root/posture_logs").append(label)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun runAppProbe(): List<String> {
    val lines = mutableListOf<String>()
    fun check(name: String, ok: Boolean, detail: String = "") {
        lines += (if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) " — $detail" else ""
    }
    val root = documentsPath() + "/app-probe"
    NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    listOf("trex_store", "trex_auth", "trex_sync").forEach { UserDefaultsStore("probe.$it").edit().clear().commit() }
    val platform = ProbePlatform(root)

    val vm = AppViewModel(platform)
    val today = localToday()
    check("앱 상태 생성·초기 계획", vm.workoutPlan.size == todayPlan.size, "계획 ${vm.workoutPlan.size}개")
    check("오늘 날짜", vm.calendarDay == todayEpochDay(), "${vm.calendarDay} · ${monthDayLabel(today)}(${koreanDayOfWeek(today.dayOfWeek)})")

    vm.addWater(0)
    val reread = TrexStore(UserDefaultsStore("probe.trex_store")).loadWater()?.get(todayEpochDay())
    check("저장 → 새 저장소로 다시 읽기", vm.waterFor(0) == 1 && reread == 1, "앱 상태 ${vm.waterFor(0)}잔 · 다시 읽음 ${reread}잔")
    val syncPrefs = UserDefaultsStore("probe.trex_sync")
    check("변경 알림 → 동기화 버전 표시", syncPrefs.contains("v_water") && syncPrefs.getBoolean("d_water", false),
        "v_water=${syncPrefs.getLong("v_water", -1)} dirty=${syncPrefs.getBoolean("d_water", false)}")
    vm.addWater(0)
    check("같은 저장소의 두 번째 변경", vm.waterFor(0) == 2)

    withTimeoutOrNull(10_000) { while (vm.muscleLoadLoading) delay(50) }
    val workout = vm.workoutPlan.first { MuscleLoadCatalog.find(it.name) != null }
    vm.recordCompletedSession(60, completedPlan = listOf(workout.copy(done = true)), elapsedByWorkout = mapOf(workout.id to 60))
    delay(200)
    withTimeoutOrNull(10_000) { while (vm.muscleLoadLoading) delay(50) }
    val ledger = FileMuscleLoadDatabase("$root/muscle_load.json").sync(emptyList(), emptyList(), nowMillis(), emptyMap())
    check("세션 기록 → 근육 장부 파일", vm.muscleLoadError == null && ledger.sets.size == 1 && ledger.doses.isNotEmpty(),
        "${workout.name} · 세트 ${ledger.sets.size} · 부하 ${ledger.doses.values.sumOf { it.size }}칸 · 오류 ${vm.muscleLoadError}")
    check("오늘 기록", vm.todayRecord?.items?.size == 1, "${vm.todayRecord?.dayLabel} ${vm.todayRecord?.dateLabel}")

    vm.labelPostureSet("probe-set", actualReps = 10, repsSource = "edited", form = null)
    delay(1_500)
    val labels = readTextFile("$root/posture_logs/labels/set_labels.jsonl").orEmpty()
    val truth = readTextFile("$root/posture_logs/rep_truth.csv").orEmpty()
    check("자가 라벨 두 파일(SetLabelFormat)", labels.contains("\"set_id\":\"probe-set\"") && truth.startsWith(SetLabelFormat.TRUTH_HEADER + "\n") && truth.contains("probe-set,10,10,"),
        labels.trim())

    check("UUID v3 = java nameUUIDFromBytes", nameUuidFromBytes("legacy:20383:0:플랭크:0".encodeToByteArray()) == "efde0cd4-d75f-3f93-a57c-0afee5d22c6c")
    lines += "INFO 지금 ${isoUtcSeconds()} · ${currentHour()}시 · 무작위 UUID ${newUuid()}"

    val http = try {
        TrexApi(platform.apiBaseUrl, platform.http) { null }.syncItems()
        "200(예상 밖 — 토큰 없이 받았다)"
    } catch (e: ApiException) {
        "ApiException ${e.status} ${e.message}"
    } catch (e: NetworkException) {
        "NetworkException ${e.message}"
    }
    check("HTTP 전송(NSURLSession) — 토큰 없이 401", http.startsWith("ApiException 401"), http)
    return lines
}
