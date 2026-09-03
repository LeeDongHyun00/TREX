package com.example.trex_kotlin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.res.Configuration
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.AnalyzerStats
import com.example.trex_kotlin.posture.BaselineStore
import com.example.trex_kotlin.posture.CoachCues
import com.example.trex_kotlin.posture.Direction
import com.example.trex_kotlin.posture.CoachEvent
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.FeatureAggregator
import com.example.trex_kotlin.posture.GravityTracker
import com.example.trex_kotlin.posture.CoverageReport
import com.example.trex_kotlin.posture.FLOOR_RULES_ASSET
import com.example.trex_kotlin.posture.FloorCoverage
import com.example.trex_kotlin.posture.FloorFeatureExtractor
import com.example.trex_kotlin.posture.InferencePhase
import com.example.trex_kotlin.posture.InferencePolicy
import com.example.trex_kotlin.posture.LiveCoach
import com.example.trex_kotlin.posture.ModeStore
import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.POSE_CONNECTIONS
import com.example.trex_kotlin.posture.PoseModel
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.PostureAnalyzer
import com.example.trex_kotlin.posture.PostureRule
import com.example.trex_kotlin.posture.RepCounter
import com.example.trex_kotlin.posture.RepMetrics
import com.example.trex_kotlin.posture.RepRecord
import com.example.trex_kotlin.posture.RuleHighlight
import com.example.trex_kotlin.posture.RuleStatus
import com.example.trex_kotlin.posture.RuleResult
import com.example.trex_kotlin.posture.PostureRuleSet
import com.example.trex_kotlin.posture.PostureScope
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.SCREEN_UP
import com.example.trex_kotlin.posture.SetLog
import com.example.trex_kotlin.posture.SetLogStore
import com.example.trex_kotlin.posture.SpeechCoach
import com.example.trex_kotlin.posture.SubjectId
import com.example.trex_kotlin.posture.ThermalMonitor
import com.example.trex_kotlin.posture.Verdict
import com.example.trex_kotlin.posture.gravityUpInWorld
import com.example.trex_kotlin.posture.withFeatures
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 실시간 자세 평가 세션 (실 엔진).
 *
 * PostureLabScreen(개발용)과 같은 파이프라인 — CameraX 640×480 분석 스트림 → 열/단계 인지
 * 스케줄러(300ms) → MediaPipe PoseLandmarker(GPU 폴백 CPU) → IMU 중력축 체간 좌표계 피처
 * → rules_mp_v0 규칙 평가 → LiveCoach 가 '처음부터/점점 흐트러짐/교정됨' 을 판별해
 * 화면 카드와 음성으로 안내한다. 시뮬레이션이던 세션 자세 평가의 실제 구현이다.
 */

/** 앱 운동명 → AIHub 규칙 종목 매핑 — 여기 있는 운동만 자세 교정을 켤 수 있다. */
val postureExerciseMap: Map<String, String> = mapOf(
    "기본 스쿼트" to "바벨 스쿼트",
    "바벨 스쿼트" to "바벨 스쿼트",
    "런지" to "스텝 포워드 다이나믹 런지",
    "바벨 런지" to "바벨 런지",
    "사이드 런지" to "사이드 런지",
    "크로스 런지" to "크로스 런지",
    "바벨 데드리프트" to "바벨 데드리프트",
    "굿모닝" to "굿모닝",
    "딥스" to "딥스",
    "오버헤드 프레스" to "오버 헤드 프레스",
    "덤벨 컬" to "덤벨 컬",
    "바벨 컬" to "바벨 컬",
    "사이드 레터럴 레이즈" to "사이드 레터럴 레이즈",
    "프런트 레이즈" to "프런트 레이즈",
    "랫풀 다운" to "랫풀 다운",
    "업라이트로우" to "업라이트로우",
    "스탠딩 사이드 크런치" to "스탠딩 사이드 크런치",
    "스탠딩 니업" to "스탠딩 니업",
    "행잉 레그 레이즈" to "행잉 레그 레이즈",
    // 바닥 종목 (rules_floor_v0.1 — 2D 평면 경로, 전부 beta·임계값 미보정, spec §25/§25a).
    // 바이시클 크런치는 MP 충실도 게이트 후 남은 규칙이 없어 제외.
    "푸쉬업" to "푸시업",
    "니 푸쉬업" to "니푸쉬업",
    "플랭크" to "플랭크",
    "크런치" to "크런치",
    "레그 레이즈" to "라잉 레그 레이즈",
    "힙 쓰러스트" to "힙쓰러스트",
    "시저 크로스" to "시저크로스",
    "Y 레이즈" to "Y - Exercise",
)

fun Workout.postureSupported(): Boolean = postureExerciseMap.containsKey(name)

/** 추론·샘플 간격 (랩과 동일 — 로그의 프레임 간격이 재보정 창 정의와 맞아야 한다) */
private const val SESSION_SAMPLE_INTERVAL_MS = 300L

/** 이 프레임 수 미만이면 판정도 로그도 남기지 않는다 (랩의 MIN_FRAMES_FOR_VERDICT 과 동일) */
private const val MIN_FRAMES_FOR_LOG = 8

/** 커버리지 경고를 띄우기까지 연속으로 막혀야 하는 프레임 수 (300ms × 3 ≈ 1초) */
private const val COVERAGE_STREAK = 3

/**
 * 초반 창 앵커 폴백 (spec §31). 렙 카운터가 있는 종목은 **첫 렙 완료**에 앵커하지만, 그때까지 마냥 기다리면
 * 등척성·느린 종목에서 세트 내내 판정이 없다. 렙 신호가 없는 종목(데드리프트·컬·레이즈류)은 짧게, 있는 종목은 길게.
 */
private const val ANCHOR_FALLBACK_NO_COUNTER_MS = 4_000L
private const val ANCHOR_FALLBACK_WITH_COUNTER_MS = 10_000L

/** '참고(베타)' 배너 갱신 최소 간격 — 말하지 않는 표시라도 매 프레임 바뀌면 읽을 수 없다 */
private const val PROVISIONAL_NOTE_GAP_MS = 3_000L

/** 세트당 무효 렙 사유 발화 상한 — 같은 문장을 렙마다 반복하면 잔소리가 되고 자세 지적을 큐 뒤로 민다 */
private const val MAX_INVALID_CUES = 2

/** 촬영 안내 음성 최소 간격 — 자세를 고치는 데 시간이 걸리므로 자주 말하지 않는다 */
private const val COVERAGE_SPEAK_GAP_MS = 8_000L

/** 세트 경계 발화(요약 + 다음 종목 시작 안내)를 보호하는 시간 — 그동안 코치·커버리지 발화는 flush 대신 큐에 붙는다 */
private const val SET_BOUNDARY_SPEECH_MS = 9_000L

@Composable
fun PostureLiveSessionScreen(
    workout: Workout,
    index: Int,
    total: Int,
    timeLeft: Int,
    totalSeconds: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    onSetReport: (PostureSetReport) -> Unit = {},
    /** 카메라 없이 이 운동을 **타이머로** 이어간다 — 건너뛰기가 아니다. */
    onFallbackToTimer: () -> Unit = {},
    /** 세션 스코프 스피커 — 이 화면보다 오래 산다. 세트 종료 요약이 다음 운동(타이머 화면)으로 넘어가며 끊기지 않게 TrexApp 이 소유한다. */
    speech: SpeechCoach,
) {
    val c = Trex.c
    KeepScreenOn()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val aihubExercise = postureExerciseMap[workout.name] ?: workout.name

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(granted) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        if (asked) {
            Column(
                Modifier.fillMaxSize().background(c.bg).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(82.dp).clip(CircleShape).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.warn, modifier = Modifier.size(34.dp)) }
                Text("카메라 권한이 필요해룡", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                Text(
                    "이 운동을 자세 평가 없이 타이머로 이어갈 수 있어요.",
                    color = c.text2, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // onNext 는 이 운동을 '완료' 처리하고 다음으로 넘긴다 — 권한을 거부했다고 운동을 건너뛰면 안 된다.
                // 같은 운동을 타이머 화면으로 다시 열도록 호출부에 맡긴다.
                Cta("타이머로 계속", onClick = onFallbackToTimer, modifier = Modifier.padding(top = 28.dp).fillMaxWidth())
                GhostButton("나가기", onClick = onExit, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
            }
        }
        return
    }

    // ---- 실 엔진 파이프라인 (서서 하는 종목 + 바닥 종목 규칙 병합 — PostureLabScreen 과 동일 패턴)
    var ruleSet by remember { mutableStateOf<PostureRuleSet?>(null) }
    var floorExercises by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        runCatching {
            val standing = PostureRuleSet.load(context)
            try {
                val floor = PostureRuleSet.load(context, FLOOR_RULES_ASSET)
                floorExercises = floor.exercises.toSet()
                PostureRuleSet("${standing.version}+${floor.version}", standing.generated, standing.rules + floor.rules)
            } catch (_: Throwable) {
                standing
            }
        }.onSuccess { ruleSet = it }
    }
    // 바닥 종목은 중력/3D 피처 대신 2D 평면 피처를 쓴다 (spec §25). 분석 스레드에서 매 프레임 읽으므로 ref 로 전달.
    val isFloorExercise = aihubExercise in floorExercises
    val floorRef = remember { booleanArrayOf(false) }
    floorRef[0] = isFloorExercise
    val floorExtractor = remember { FloorFeatureExtractor() }

    // ---- 개인 기준선(정상-앵커 재배치, spec §25c/§25d): BaselineGuideScreen 이 수집한 정자세 k세트 중앙값.
    //      바닥 규칙 임계값은 AIHub 채택 뷰 투영에 묶여 있어(뷰 간 플래그율 33%p 요동) 사용자의 실제 폰
    //      시점으로 '위치'를 옮겨야 한다. 재투영 실측: 거치가 정확하면 중립, 방위가 어긋나면 +0.11 복구(보험).
    //      수집만 하고 세션에서 안 쓰면 죽은 기능 — 여기서 소비한다.
    val baselineStore = remember { BaselineStore(context) }
    val baselineRef = remember { arrayOfNulls<Map<String, Float>>(1) }
    var baselineActive by remember { mutableStateOf(false) }

    // ---- 자동 렙 카운터 (spec §27): 종목별 렙 신호의 히스테리시스 사이클. 분석 스레드에서 갱신.
    //      등척성(플랭크)·미등록 종목은 null. 카운트는 beta — ±1 오차가 구조적이라 참고 표시.
    val repRef = remember { arrayOfNulls<RepCounter>(1) }
    var repCount by remember { mutableIntStateOf(0) }      // 유효 렙
    var repInvalid by remember { mutableIntStateOf(0) }    // ROM 미달 렙 — 코치: 무효+사유 발화, 기록: 파셜 집계만 (§29)
    val repInvalidRef = remember { intArrayOf(0) }
    // 무효 렙 사유 발화 횟수 — 세트당 상한(MAX_INVALID_CUES). 렙마다 같은 말을 반복하면 코칭이 잔소리가 되고,
    // 정작 들어야 할 자세 지적이 큐 뒤로 밀린다.
    val invalidCuesRef = remember { intArrayOf(0) }
    // 앵커 폴백 기준시각 — 이 종목에서 사람이 처음 잡힌 때(0 = 아직). 종목 경계에서 리셋한다.
    val detectStartRef = remember { longArrayOf(0L) }
    var anchored by remember { mutableStateOf(false) }
    val anchoredRef = remember { booleanArrayOf(false) }
    /** 앵커가 잡힌 세트 상대시각(ms). 로그에 남겨 오프라인 재계산이 같은 창을 쓸 수 있게 한다. 0 = 아직. */
    val anchorAtRef = remember { longArrayOf(0L) }
    // 베타(미보정) 위반 — 말하지 않고 화면에만 '참고' 로 남긴다
    var provisionalNote by remember { mutableStateOf<String?>(null) }
    val provisionalAtRef = remember { longArrayOf(0L) }
    var provisionalHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var repFast by remember { mutableStateOf(false) }
    var repTempoMs by remember { mutableStateOf<Long?>(null) }   // 렙 간격 중앙값 — 기록 모드 계기판 (§29)
    val repRecords = remember { ArrayList<RepRecord>() }         // 렙별 극값 — 세트 로그에 남김 (분석 스레드에서 추가)
    // 위반 부위 시각화 (수정할점 #1): 위반 중 규칙의 관절을 스켈레톤에서 붉게 강조
    var violHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // ---- 세션 모드 (spec §29): 코치(초보 기본) / 기록(숙련). 종목별 저장. 정책 레이어만 바꾼다 —
    //      판정·임계값·로그는 두 모드에서 동일하게 계산된다 (모든 사용자 원칙).
    val modeStore = remember { ModeStore(context) }
    var mode by remember { mutableStateOf(CoachMode.COACH) }
    val modeRef = remember { arrayOf(CoachMode.COACH) }
    modeRef[0] = mode
    // 기록 모드에서 DRIFT 를 실제로 발화한 규칙 — RECOVERED 는 말한 드리프트에 대해서만 (안 말한 위반의 "교정됐어요" 방지)
    val trackDriftSpoken = remember { HashSet<String>() }

    // ---- 촬영 커버리지 (spec §25b): 규칙이 요구하는 부위가 화면에 없으면 '왜'와 '어떻게'를 안내한다.
    //      판정 자체가 불가능한 상태이므로 자세 코칭보다 우선한다.
    val floorRules = remember(ruleSet, aihubExercise, isFloorExercise) {
        if (isFloorExercise) ruleSet?.rulesFor(aihubExercise, includeBeta = true).orEmpty() else emptyList()
    }
    // 이 종목에서 무엇을 보고 무엇을 못 보는가 (spec §31) — 시작 안내 둘째 문장과 카드 부제가 같은 값을 쓴다.
    // 데드리프트처럼 '척추의 중립' 이 전부 exclude 인 종목은 허리를 말아도 리포트가 "깨끗" 이라 말한다 — 그걸 미리 밝힌다.
    val scope = remember(ruleSet, aihubExercise) { ruleSet?.let { PostureScope.of(it, aihubExercise) } }
    val floorRulesRef = remember { arrayOfNulls<List<PostureRule>>(1) }
    floorRulesRef[0] = floorRules
    var coverage by remember { mutableStateOf(CoverageReport.OK) }
    // 한 프레임 튀는 것으로 문구가 깜빡이지 않도록, 연속으로 막힐 때만 표시한다
    val coverageStreak = remember { intArrayOf(0) }
    val lastCoverageSpeakAt = remember { longArrayOf(0L) }
    // 음소거는 스피커(세션 스코프)에 남아 다음 운동·완료 화면까지 이어진다
    // TTS 를 못 쓰는 기기에서는 렙 숫자가 통째로 사라진다 — 짧은 톤으로라도 센 것을 알린다
    val repTone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(repTone) { onDispose { runCatching { repTone?.release() } } }
    var muted by remember { mutableStateOf(speech.muted) }
    LaunchedEffect(muted) { speech.muted = muted }
    // 세트 경계 발화(요약 + 다음 종목 시작 안내)가 끝날 때까지 코치·커버리지 발화는 큐에 붙인다(flush 금지) — 요약이 통째로 사라지지 않게
    val boundaryUntil = remember { longArrayOf(0L) }

    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu = true) }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val policy = remember { InferencePolicy(sampleIntervalMs = SESSION_SAMPLE_INTERVAL_MS) }
    val lastInferAt = remember { longArrayOf(0L) }
    val thermal = remember { ThermalMonitor(context) }
    val thermalRef = remember { intArrayOf(InferencePolicy.THERMAL_NONE) }
    val gravity = remember { GravityTracker(context) }
    DisposableEffect(Unit) {
        gravity.start()
        thermal.start(ContextCompat.getMainExecutor(context)) { s -> thermalRef[0] = s }
        onDispose {
            gravity.stop()
            thermal.stop()
            executor.shutdown()
        }
    }
    // 회전해도 Activity 가 유지되므로(configChanges), 화면 회전값은 configuration 변화마다 다시 읽는다.
    // 분석 스레드가 매 프레임 읽으므로 ref 로도 전달한다.
    val configuration = LocalConfiguration.current
    val displayRotation = remember(configuration) {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    val rotationRef = remember { intArrayOf(displayRotation) }
    rotationRef[0] = displayRotation
    // ImageAnalysis 는 바인딩 시점의 회전값을 갖고 있어, 회전 후에는 직접 갱신해야 이미지가 바로 선다.
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    LaunchedEffect(displayRotation) { analysisRef[0]?.targetRotation = displayRotation }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var useFrontCamera by remember { mutableStateOf(true) }
    val frontRef = remember { booleanArrayOf(true) }
    frontRef[0] = useFrontCamera
    val pausedRef = remember { booleanArrayOf(false) }
    pausedRef[0] = paused

    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var stats by remember { mutableStateOf<AnalyzerStats?>(null) }
    var everDetected by remember { mutableStateOf(false) }
    var coachBanner by remember { mutableStateOf<CoachEvent?>(null) }
    // 자세 점수는 **분수**로 보여 준다 — 종목당 검증된 규칙이 2~4개뿐이라 한 건 위반이 33%p 를 깎는다.
    // "67%" 는 성적처럼 읽히지만 실제 의미는 "3가지 중 2가지 정상" 이다.
    var scoreOk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val coachRef = remember { arrayOfNulls<LiveCoach>(1) }

    // ---- 세트 로그 (재보정 데이터, spec §14): 실제 세션에서도 남긴다.
    //      바닥 종목은 이 화면으로만 돌아가므로, 여기서 안 남기면 바닥 임계값 재보정 데이터가 아예 생기지 않는다.
    val recordedSamples = remember { ArrayList<PoseSample>() }
    val recordedTimesMs = remember { ArrayList<Long>() }
    val aggregator = remember { FeatureAggregator() }
    val logStore = remember { SetLogStore(context) }
    val subjectId = remember { SubjectId.get(context) }
    var savedSets by remember { mutableIntStateOf(0) }
    var recordedFrames by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { savedSets = runCatching { logStore.totalSets() }.getOrDefault(0) }

    // 세트 마감 — 세트 경계에서 호출된다. 종목/뷰는 **세트 시작 시점 값**을 인자로 받는다 —
    // 이 화면은 다음 운동으로 넘어가도 재생성되지 않아(AnimatedContent 의 같은 route), 지금 값을 쓰면 종목이 어긋난다.
    // 로그(재보정 데이터, §14)와 리포트(완료 화면·세트 종료 발화·기록, §30)를 한 자리에서 만든다 —
    // 자가 라벨이 로그를 가리켜야 하므로 리포트의 setId 는 SetLog 가 발급한 값을 그대로 쓴다.
    // 반환: 샘플이 MIN_FRAMES_FOR_LOG 이상이면 리포트(규칙 평가가 실패해도 results 를 비워 UNJUDGED 로), 미만이면 null(로그도 없음).
    // 멱등 — 샘플을 비우므로 같은 세트의 두 번째 호출(onDispose 안전망)은 null 이고 아무것도 남기지 않는다.
    val finalizeRef = remember { arrayOfNulls<(String, String, Boolean) -> PostureSetReport?>(1) }
    finalizeRef[0] = fin@{ ex: String, label: String, floor: Boolean ->
        val rs = ruleSet ?: return@fin null
        val samples: List<PoseSample>
        val times: List<Long>
        val results: List<RuleResult>
        // 분석 스레드의 aggregator.add 와 같은 락 — 평가 도중 프레임이 끼어들면 CME 로 결과가 비어 멀쩡한 세트가 UNJUDGED 가 된다
        synchronized(recordedSamples) {
            samples = ArrayList(recordedSamples)
            times = ArrayList(recordedTimesMs)
            recordedSamples.clear()
            recordedTimesMs.clear()
            val frames = aggregator.frameCount
            results = if (frames >= MIN_FRAMES_FOR_LOG) {
                runCatching { rs.evaluate(ex, aggregator, true, MIN_FRAMES_FOR_LOG, baselineRef[0]) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            aggregator.reset()
        }
        if (samples.size < MIN_FRAMES_FOR_LOG) return@fin null
        // onset(처음부터/점점/교정됨) 분류는 여기서 — coachRef 는 다음 종목의 LaunchedEffect 에서 새 코치로 바뀐다
        val onset = runCatching { coachRef[0]?.summarize().orEmpty() }.getOrDefault(emptyList())
        val t0 = times.firstOrNull() ?: 0L
        val rc = repRef[0]
        val reps: List<RepRecord>?
        val repTimes: List<Long>?
        synchronized(repRecords) {
            reps = if (rc != null) ArrayList(repRecords) else null
            repTimes = rc?.repTimesMs?.toList()   // 분석 스레드의 onFrame 과 같은 락 안에서 복사
            repRecords.clear()
        }
        val log = SetLog.build(
            exercise = ex,
            samples = samples,
            results = results,
            rulesVersion = rs.version,
            model = PoseModel.FULL.label,
            delegate = stats?.delegate ?: "-",
            frontCamera = useFrontCamera,
            sampleIntervalMs = SESSION_SAMPLE_INTERVAL_MS,
            sampleTimesMs = times.map { it - t0 },
            subjectId = subjectId,
            note = "session:$label" + if (floor) " floor" else "",
            repCount = rc?.reps,
            // 프레임 t_ms 와 같은 기준(세트 시작 상대시각)으로 — 첫 로그에서 절대 epoch 로 남던 결함 수정
            repTimesMs = repTimes?.map { it - t0 },
            repSignal = rc?.signal?.feature,
            repInvalid = if (rc != null) repInvalidRef[0] else null,
            // 렙별 극값 t 도 세트 상대시각으로 (프레임·repTimesMs 와 같은 기준)
            repRecords = reps?.map { it.copy(tMs = it.tMs - t0) },
            mode = if (modeRef[0] == CoachMode.TRACK) "track" else "coach",
            // 집계 창의 시작 — results 가 이 시점 이후 프레임만 본다는 사실을 로그에 남긴다
            anchorTMs = anchorAtRef[0].takeIf { it > 0L }?.let { it - t0 },
        )
        // 분석 executor 는 화면 종료 시 shutdown 되므로 순서에 의존하지 않도록 별도 스레드에서 기록한다.
        Thread {
            runCatching {
                logStore.append(log)
                savedSets = logStore.totalSets()
            }
        }.start()
        PostureSetReport.build(
            setId = log.setId,
            exercise = ex,
            workoutName = label,
            mode = modeRef[0],
            frames = samples.size,
            baselineActive = baselineRef[0] != null,
            results = results,
            onset = onset,
            // 렙 카운터 미적용 종목은 null. 유효 = 전체 사이클 − ROM 미달(코치의 "무효" = 기록의 "파셜", §29)
            repsValid = rc?.let { it.reps - repInvalidRef[0] },
            repsPartial = rc?.let { repInvalidRef[0] },
            tempoMs = repTimes?.let { RepMetrics.medianPeriodMs(it) },
        )
    }
    // 세트(운동) 경계 안전망: ✓/✕ 가 이미 마감했으면 멱등으로 null. 마감 없이 화면이 사라질 때(액티비티 종료 등)만
    // 여기서 로그·리포트가 남는다 — 발화는 없다(화면이 이미 없다).
    DisposableEffect(workout.id) {
        val ex = aihubExercise
        val label = workout.name
        val floor = isFloorExercise
        recordedFrames = 0
        onDispose {
            finalizeRef[0]?.invoke(ex, label, floor)?.let(onSetReport)
            recordedFrames = 0
        }
    }
    LaunchedEffect(ruleSet, workout.id) {
        val rs = ruleSet ?: return@LaunchedEffect
        val baselineValues = runCatching { baselineStore.load().valuesFor(aihubExercise) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        baselineRef[0] = baselineValues
        baselineActive = baselineValues != null
        // requireAnchor: 초반 창이 준비 동작(폰 놓고 걸어오기)을 '정상 기준' 으로 삼으면 첫 코칭이 "처음부터…" 오탐이 된다.
        // speakBeta=false: 미보정 규칙은 화면·리포트에 '참고' 로만 남기고 음성은 검증된 규칙만 낸다 (§28 오탐 3건이 전부 베타).
        coachRef[0] = LiveCoach(rs, aihubExercise, baseline = baselineValues, requireAnchor = true, speakBeta = false)
        coachBanner = null   // 이전 종목의 배너·ⓘ 근거 주석이 새 종목에 오귀속되지 않도록
        repRef[0] = RepCounter.forExercise(aihubExercise)
        repCount = 0
        repInvalid = 0
        repInvalidRef[0] = 0
        invalidCuesRef[0] = 0
        detectStartRef[0] = 0L
        anchored = false
        anchoredRef[0] = false
        anchorAtRef[0] = 0L
        scoreOk = null
        provisionalNote = null
        provisionalAtRef[0] = 0L
        provisionalHighlight = emptySet()
        repFast = false
        repTempoMs = null
        synchronized(repRecords) { repRecords.clear() }
        mode = modeStore.get(aihubExercise)
        trackDriftSpoken.clear()
        floorExtractor.reset()   // 접지선 추정은 세트(운동) 단위 상태
        // 커버리지는 바닥 종목 루프에서만 갱신되므로, 바닥→서서 하는 종목으로 넘어갈 때 여기서 안 풀면 새 종목 내내 코칭이 막힌다
        coverage = CoverageReport.OK
        coverageStreak[0] = 0
        if (!muted) {
            // 큐에 추가(flush=false) — ✓ 에서 말한 직전 세트의 요약 문장을 끊지 않도록. 첫 세트는 큐가 비어 있어 바로 나온다.
            val placement = if (aihubExercise in floorExercises) {
                "휴대폰을 몸 옆에 두세요. 발쪽으로 치우치지 않게요"
            } else {
                "전신이 화면에 들어오게 서 주세요"
            }
            // 셋째 문장: 이 종목에서 보는 것·못 보는 것. 침묵을 "완벽하다" 로 읽지 않게 미리 밝힌다 (spec §31).
            val scopeLine = scope?.startLine
            speech.speak(
                "${workout.name} 자세 평가를 시작합니다. " + placement + (scopeLine?.let { ". " + it } ?: ""),
                flush = false,
            )
        }
    }

    // FIT_CENTER: 카메라가 보는 **전체**를 보여준다. FILL_CENTER 는 4:3 영상을 긴 화면에 채우느라
    // 좌우(세로 모드) 또는 상하(가로 모드)를 잘라내, 사용자가 실제 분석 범위보다 좁게 보고 프레이밍을 그르쳤다.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    LaunchedEffect(useFrontCamera) {
        val provider = context.awaitCameraProviderLive()
        provider.unbindAll()
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .setTargetRotation(rotationRef[0])
            .build()
        analysisRef[0] = analysis   // 회전 시 targetRotation 갱신용
        analysis.setAnalyzer(executor) { image ->
            val now = System.currentTimeMillis()
            val phase = if (pausedRef[0]) InferencePhase.IDLE else InferencePhase.RECORDING
            if (!policy.shouldInfer(now, lastInferAt[0], phase, thermalRef[0])) {
                image.close()
                return@setAnalyzer
            }
            lastInferAt[0] = now
            try {
                val up = gravity.gravityDevice
                    ?.let { gravityUpInWorld(it, rotationRef[0], frontRef[0]) }
                    ?: SCREEN_UP
                val s = analyzer.analyze(image, now, up)
                sample = s
                stats = analyzer.stats()
                if (s.detected) everDetected = true
                if (s.detected && !pausedRef[0]) {
                    // 바닥 종목: 중력 기반 3D 피처 대신 2D 평면 피처 (가림 시 피처 단위 유보 포함)
                    val features = if (floorRef[0]) {
                        floorExtractor.compute(s.normalizedXy, s.visibility, s.imageWidth, s.imageHeight)
                    } else {
                        s.features
                    }
                    // 촬영 커버리지 — 규칙이 요구하는 부위가 화면에 있는가
                    if (floorRef[0]) {
                        val rep = FloorCoverage.analyze(s.normalizedXy, s.visibility, floorRulesRef[0].orEmpty(), frontRef[0])
                        if (rep.ok) {
                            coverageStreak[0] = 0
                            if (!coverage.ok) coverage = CoverageReport.OK
                        } else {
                            coverageStreak[0]++
                            if (coverageStreak[0] >= COVERAGE_STREAK) coverage = rep
                        }
                    }
                    // 재보정용 원본 샘플 — 바닥 종목은 규칙이 실제로 쓴 2D 피처를 그대로 남긴다.
                    // aggregator 도 같은 락 안에서 — 세트 마감의 evaluate/reset 과 겹치면 CME 로 결과가 비어 UNJUDGED 오판정이 난다
                    synchronized(recordedSamples) {
                        aggregator.add(features)
                        recordedSamples.add(if (floorRef[0]) s.withFeatures(features) else s)
                        recordedTimesMs.add(now)
                        recordedFrames = recordedSamples.size
                    }
                    repRef[0]?.let { rc ->
                        // repTimesMs 갱신은 세트 마감의 복사와 같은 락 안에서
                        val completed = synchronized(repRecords) { rc.onFrame(now, features[rc.signal.feature]) }
                        if (completed) {
                            // 첫 렙이 끝났다 = 여기부터가 진짜 운동 구간. 초반 창과 **세트 집계**를 여기로 옮긴다 (spec §31).
                            if (coachRef[0]?.anchor() == true) {
                                anchored = true; anchoredRef[0] = true; anchorAtRef[0] = now
                                // 세트 점수·판정의 집계기도 같이 비운다 — 준비 동작이 range/min/max 통계를 통째로 뒤집는다
                                synchronized(recordedSamples) { aggregator.reset() }
                            }
                            // ROM 유효성 (수정판): 사이클 극값이 데이터 기준 미달이면 미달 렙.
                            // 코치 모드 = 무효로 판정하고 사유를 말한다 (REP_VALIDITY.md, 심판 방식).
                            // 기록 모드 = "파셜"로 집계만 — 숙련자의 파셜은 기법이지 잘못이 아니다 (§29).
                            val valid = rc.signal.isValidRep(rc.lastCycleMin, rc.lastCycleMax)
                            synchronized(repRecords) { repRecords.add(RepRecord(now, rc.lastCycleMin, rc.lastCycleMax, valid)) }
                            if (modeRef[0] == CoachMode.TRACK) {
                                if (valid != false) repCount++ else { repInvalid++; repInvalidRef[0] = repInvalid }
                                // 카운트만 말한다 — 숙련자가 세는 숫자는 파셜 포함 전체
                                if (!muted) speakRep(speech, repTone, repCount + repInvalid)
                            } else if (valid != false) {
                                repCount++
                                if (!muted) speakRep(speech, repTone, repCount)   // 숫자는 큐에 추가 — 코칭 문구를 끊지 않음
                            } else {
                                repInvalid++
                                repInvalidRef[0] = repInvalid
                                // ROM 판별력이 검증된 종목만 사유를 말한다 — 미검증 종목의 무효 판정은 방향 중립 문구라
                                // "끝까지 움직이세요" 가 오히려 잘못된 가동범위를 유도할 수 있다 (REP_VALIDITY.md).
                                if (!muted && rc.signal.romValidated && invalidCuesRef[0] < MAX_INVALID_CUES) {
                                    invalidCuesRef[0]++
                                    speech.speak(rc.signal.invalidCue, flush = false)
                                }
                            }
                            repTempoMs = RepMetrics.medianPeriodMs(rc.repTimesMs)
                            // 빠른 렙 자가진단: 주기가 1.5s 아래면 3.3fps 로는 놓칠 수 있다 (렙당 4샘플 하한 실측)
                            repFast = (rc.periodMs ?: Long.MAX_VALUE) < 1_500L
                        }
                    }
                    coachRef[0]?.let { coach ->
                        // 앵커 폴백: 렙 신호가 없는 종목(등척성·컬·레이즈류)이거나 첫 렙이 너무 늦으면 시간으로 앵커한다.
                        if (!coach.isAnchored) {
                            if (detectStartRef[0] == 0L) detectStartRef[0] = now
                            val wait = if (repRef[0] == null) ANCHOR_FALLBACK_NO_COUNTER_MS else ANCHOR_FALLBACK_WITH_COUNTER_MS
                            if (now - detectStartRef[0] >= wait && coach.anchor()) {
                                anchored = true; anchoredRef[0] = true; anchorAtRef[0] = now
                                synchronized(recordedSamples) { aggregator.reset() }
                            }
                        }
                        coach.onFrame(features)
                        val ev = coach.evaluate(now)
                        val track = modeRef[0] == CoachMode.TRACK
                        // 강조를 두 갈래로: 검증된(ship) 위반만 붉게, 미보정(beta)은 '참고' 색 — 같은 붉은색이면
                        // 말하지 않기로 한 규칙이 화면에서는 확신처럼 보인다.
                        val shipStates = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
                        val betaStates = coach.lastStates.filter { it.rule.status == RuleStatus.BETA }
                        // 기록 모드: 위반 강조 없음 — 모집단 임계 기준 "틀림" 표시는 스타일을 오판할 수 있다 (§29)
                        violHighlight = if (track) emptySet() else RuleHighlight.forViolations(shipStates)
                        provisionalHighlight = if (track) emptySet() else RuleHighlight.forViolations(betaStates)
                        // 베타 위반은 말하지 않는 대신 화면에 '참고' 로 남긴다 — 침묵이 "이상 없음" 으로 읽히면 안 된다
                        if (!track && now - provisionalAtRef[0] > PROVISIONAL_NOTE_GAP_MS) {
                            provisionalAtRef[0] = now
                            provisionalNote = betaStates.firstOrNull { it.recent == Verdict.VIOLATION }?.let { st ->
                                val cue = CoachCues.cueFor(st.rule, st.direction ?: Direction.PRIMARY)
                                PostureSetReport.splitCue(if (st.kind == OnsetKind.DRIFT) cue.drift else cue.habit).first
                            }
                        }
                        // 세트 경계 발화(요약·시작 안내)가 나가는 동안은 끊지 않고 뒤에 붙인다
                        val flush = now > boundaryUntil[0]
                        // 커버리지가 막힌 동안에는 자세 지적 대신 촬영 안내를 말한다 (판정 근거가 없으므로) — 바닥 종목만의 상태
                        if (floorRef[0] && !coverage.ok) {
                            if (now - lastCoverageSpeakAt[0] > COVERAGE_SPEAK_GAP_MS) {
                                lastCoverageSpeakAt[0] = now
                                speech.speak(coverage.message + ". " + coverage.fix, flush = flush)
                            }
                        } else if (ev != null) {
                            if (!track) {
                                coachBanner = ev
                                speech.speak(ev.message, flush = flush)
                            } else when (ev.kind) {
                                // §29: 세트 내 변화(피로 드리프트)만 알린다 — 숙련자에게도 정보
                                OnsetKind.DRIFT -> {
                                    coachBanner = ev
                                    speech.speak(ev.message, flush = flush)
                                    trackDriftSpoken.add(ev.rule.id)
                                }
                                // 말한 드리프트가 돌아왔을 때만 "교정됐어요" — 침묵한 위반의 교정 발화는 어리둥절
                                OnsetKind.RECOVERED -> if (trackDriftSpoken.remove(ev.rule.id)) {
                                    coachBanner = ev
                                    speech.speak(ev.message, flush = flush)
                                }
                                // HABIT("처음부터") = 본인 스타일일 수 있음 — 판정은 로그에만, 잔소리 없음
                                OnsetKind.HABIT -> {}
                            }
                        }
                        // 점수는 리포트와 같은 분모로 — 검증된(ship) 규칙만. 베타를 섞으면 화면과 리포트가 다른 숫자를 말한다.
                        val states = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
                        val ok = states.count { it.recent == Verdict.OK }
                        val bad = states.count { it.recent == Verdict.VIOLATION }
                        if (ok + bad > 0) scoreOk = ok to (ok + bad)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis) }
    }

    // ---- UI: 카메라 영역과 조작 영역을 **분리**한다 (spec §25c).
    //      이전에는 풀블리드 카메라 위에 패널을 얹어 하단 31% 가 가려졌고, FILL_CENTER 라 영상의
    //      좌우(세로 모드) 37% 가 잘려 보여 사용자가 프레이밍을 확인할 수 없었다.
    //      이제 FIT_CENTER 로 카메라가 보는 전체를 남는 공간에 꽉 채우고, 패널은 그 바깥에 둔다.
    val glass = if (c.isDark) Color(0xE61B2115) else Color(0xF2FFFFFF)
    val onCam = Color.White

    val cameraArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            LivePoseOverlay(
                sample = sample, mirror = useFrontCamera, tint = c.lime,
                highlight = violHighlight, provisional = provisionalHighlight,
                modifier = Modifier.fillMaxSize(),
            )
            // 상단 스크림 + 컨트롤 (영상 위에 겹치지만 사람은 보통 화면 중앙에 잡히므로 최소 높이만)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))),
            )
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIcon(
                    Icons.Rounded.Close,
                    contentDescription = "종료",
                    // 종료도 세트 마감 — 로그는 지금도 남기므로 리포트도 같이 넘긴다. 발화는 없다(화면이 바로 사라진다).
                    onClick = {
                        finalizeRef[0]?.invoke(aihubExercise, workout.name, isFloorExercise)?.let(onSetReport)
                        onExit()
                    },
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        "진행중 · ${index + 1}/$total",
                        color = onCam.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                    )
                    Text(workout.name, color = onCam, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 1.dp))
                }
                GlassIcon(Icons.Rounded.Cameraswitch, contentDescription = "카메라 전환", onClick = { useFrontCamera = !useFrontCamera })
            }
            // 인식 상태 칩
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x8C0C1008))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pulse by animateFloatAsState(if (paused || !sample.detected) 0.35f else 1f, tween(500), label = "live-pulse")
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.lime.copy(alpha = pulse)))
                Text(
                    text = when {
                        paused -> "일시정지"
                        !analyzer.isReady && stats?.error != null -> "엔진 오류"
                        sample.detected -> "인식 중 · ${sample.visibleJointCount}/33"
                        everDetected -> "관절 이탈 — 프레임 안으로"
                        else -> "전신을 화면에 맞춰주세요"
                    },
                    color = onCam, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }

    val panel: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = glass,
                contentColor = c.text,
                border = BorderStroke(1.dp, c.line),
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 모드 전환 (spec §29): 코치 = 앱이 가르침(기본) / 기록 = 본인이 기준, 앱은 기록함(숙련)
                        ModeSwitch(mode) { m ->
                            mode = m
                            modeStore.set(aihubExercise, m)
                            if (!muted) {
                                speech.speak(
                                    if (m == CoachMode.TRACK) "기록 모드. 카운트와 측정만 안내해요" else "코치 모드. 자세를 안내해요",
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 재보정 로그 상태 — 기록 중인지, 몇 세트 쌓였는지 (spec §14)
                        Text(
                            (if (baselineActive) "기준선 ✓ · " else "") + "REC $recordedFrames · 누적 ${savedSets}세트",
                            color = if (baselineActive) c.lime else c.text3, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    // 커버리지가 막히면 판정 자체가 불가능하므로 자세 코칭보다 먼저 보여준다 (spec §25b)
                    AnimatedContent(
                        targetState = when {
                            !coverage.ok -> coverage.message
                            coachBanner != null -> coachBanner!!.message
                            // 앵커 전에는 판정이 없다 — "좋아요" 라고 하면 아직 보지도 않은 자세를 칭찬하는 셈이다
                            sample.detected && !anchored -> "보고 있어요 — 편하게 시작하세요"
                            sample.detected -> "좋아요, 자세를 유지해 주세요"
                            isFloorExercise -> "휴대폰을 바닥 높이에, 몸 옆에서 보이게 두면 평가를 시작해룡"
                            else -> "전신과 주요 관절이 보이면 평가를 시작해룡"
                        },
                        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                        label = "coach-cue",
                    ) { msg ->
                        Text(
                            msg,
                            color = if (!coverage.ok) c.warn else Color.Unspecified,
                            fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    // 판정 근거가 조건명과 다른 규칙(감사 A/C/D)은 근거를 정직하게 밝힌다
                    val note = coachBanner?.let { CoachCues.measurementNote(it.rule) }
                    if (coverage.ok && note != null) {
                        Text(
                            "ⓘ $note",
                            color = c.text3, fontSize = 10.5.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    // 말하지 않기로 한 미보정 규칙 — 화면에는 '참고' 로 남긴다 (침묵 ≠ 이상 없음)
                    provisionalNote?.takeIf { coverage.ok && coachBanner == null }?.let { pn ->
                        Text(
                            "참고 · $pn — 아직 검증 중인 항목이에요",
                            color = Color(0xFFFFC24B), fontSize = 11.5.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    // 음성이 아예 안 되는 기기(한국어 TTS 미설치 등) — 침묵의 이유를 밝힌다
                    speech.unavailableReason?.let { reason ->
                        Text(
                            reason,
                            color = c.warn, fontSize = 10.5.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    // 해결책 — 카메라를 멀리 / 옮기기 / 각도 바꾸기
                    if (!coverage.ok) {
                        Text(
                            coverage.fix,
                            color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (coverage.blocked.isNotEmpty()) {
                            Text(
                                "판정 보류: " + coverage.blocked.keys.joinToString(", "),
                                color = c.text3, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }

                    // 타이머·점수·목표를 한 줄로 (이전에는 32sp 타이머가 세로 공간을 크게 먹었다)
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("남은 시간", color = c.text3, fontSize = 9.5.sp)
                            Text(timeLeft.asClock(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                        }
                        if (mode == CoachMode.TRACK && repRef[0] != null) {
                            // §29 계기판: 모집단 기준 점수 대신 템포(렙 간격 중앙값) — 스타일 무관한 측정치
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 14.dp)) {
                                Text("템포", color = c.text3, fontSize = 9.5.sp)
                                Text(
                                    repTempoMs?.let { String.format(java.util.Locale.US, "%.1f초", it / 1000f) } ?: "—",
                                    color = if (repTempoMs == null) c.text3 else Color.Unspecified,
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 14.dp)) {
                                Text("자세 점수", color = c.text3, fontSize = 9.5.sp)
                                Text(
                                    scoreOk?.let { (ok, n) -> "$ok/$n" } ?: "—",
                                    color = when {
                                        scoreOk == null -> c.text3
                                        scoreOk!!.first == scoreOk!!.second -> c.primaryText
                                        else -> c.warn
                                    },
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                                )
                            }
                        }
                        if (repRef[0] != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 14.dp)) {
                                Text(if (repFast) "렙(빠름·미확정)" else "렙", color = c.text3, fontSize = 9.5.sp)
                                Text(
                                    // 코치: 유효 수 기준 "5 · 무효 2". 기록: 전체 수 기준 "7 · 파셜 2" — 발화 숫자와 일치 (§29)
                                    if (mode == CoachMode.TRACK) {
                                        (repCount + repInvalid).toString() + if (repInvalid > 0) " · 파셜 " + repInvalid else ""
                                    } else {
                                        repCount.toString() + if (repInvalid > 0) " · 무효 " + repInvalid else ""
                                    },
                                    color = if (repFast) c.warn else Color.Unspecified,
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("목표", color = c.text3, fontSize = 9.5.sp)
                            Text(workout.reps, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                        }
                    }
                    Box(Modifier.padding(top = 8.dp)) {
                        TrackBar(progress = 1f - (timeLeft / totalSeconds.toFloat()), height = 5.dp)
                    }

                    Row(
                        Modifier.padding(top = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    ) {
                        RoundIcon(
                            if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            onClick = { muted = !muted },
                            size = 44.dp,
                            contentDescription = "음성 안내",
                        )
                        Surface(
                            onClick = onTogglePause,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = c.primary,
                            contentColor = Color.White,
                            shadowElevation = 8.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = "일시정지", modifier = Modifier.size(23.dp))
                            }
                        }
                        RoundIcon(
                            Icons.Rounded.Check,
                            onClick = {
                                val r = finalizeRef[0]?.invoke(aihubExercise, workout.name, isFloorExercise)
                                r?.let(onSetReport)
                                // 세트 요약 발화는 진행 중인 렙 숫자·코칭보다 우선(flush). 스피커가 세션 스코프라 다음 운동이
                                // 타이머 화면이어도, 마지막 운동이어도 끊기지 않는다 — 완료 화면의 세션 요약은 이 뒤에 큐로 붙는다.
                                if (r != null && !muted) {
                                    speech.speak(r.voiceLine, flush = true)
                                    boundaryUntil[0] = System.currentTimeMillis() + SET_BOUNDARY_SPEECH_MS
                                }
                                onNext()
                            },
                            size = 44.dp,
                            contentDescription = "완료",
                        )
                    }
                }
            }
        }
    }

    // 세로: 카메라가 남는 세로 공간을 전부 차지하고 패널은 그 아래. 가로: 카메라 좌측, 패널 우측 고정폭.
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                cameraArea(Modifier.weight(1f).fillMaxHeight())
                panel(
                    Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                cameraArea(Modifier.fillMaxWidth().weight(1f))
                panel(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 렙 카운트 알림 — 음성이 되면 숫자로, 안 되면 짧은 톤으로. 코칭 문구를 끊지 않게 큐에 붙인다. */
private fun speakRep(speech: SpeechCoach, tone: ToneGenerator?, n: Int) {
    if (speech.ready) {
        speech.speak(n.toString(), flush = false)
    } else {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }
    }
}

@Composable
private fun GlassIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = Color(0x660C1008),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp))
        }
    }
}

/** 코치/기록 모드 전환 (spec §29). 종목별로 저장되므로 스쿼트는 기록, 새 종목은 코치일 수 있다. */
@Composable
private fun ModeSwitch(mode: CoachMode, onSelect: (CoachMode) -> Unit) {
    val c = Trex.c
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(CoachMode.COACH to "코치", CoachMode.TRACK to "기록").forEach { (m, label) ->
            val sel = m == mode
            Surface(
                onClick = { if (!sel) onSelect(m) },
                shape = RoundedCornerShape(999.dp),
                color = if (sel) c.primary else Color.Transparent,
                contentColor = if (sel) Color.White else c.text2,
            ) {
                Text(
                    label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun LivePoseOverlay(
    sample: PoseSample,
    mirror: Boolean,
    tint: Color,
    /** 검증된(ship) 규칙 위반 — 붉게. */
    highlight: Set<Int> = emptySet(),
    /** 미보정(beta) 규칙 위반 — 노랗게. 말하지 않는 판정이므로 붉은색과 같은 확신을 주면 안 된다 (spec §31). */
    provisional: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (!sample.detected || sample.imageWidth <= 0) return@Canvas
        val imgW = sample.imageWidth.toFloat()
        val imgH = sample.imageHeight.toFloat()
        // PreviewView 가 FIT_CENTER 이므로 여기서도 min — max(FILL)를 쓰면 스켈레톤이 어긋난다
        val scale = minOf(size.width / imgW, size.height / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val dx = (size.width - drawW) / 2f
        val dy = (size.height - drawH) / 2f

        fun point(i: Int): Offset {
            val px = dx + sample.normalizedXy[i * 2] * drawW
            val py = dy + sample.normalizedXy[i * 2 + 1] * drawH
            return Offset(if (mirror) size.width - px else px, py)
        }

        val warn = Color(0xFFFF5A5A)
        val provisionalColor = Color(0xFFFFC24B)   // 미보정 규칙 = 호박색. 붉은색과 같은 확신을 주지 않는다
        POSE_CONNECTIONS.forEach { (a, b) ->
            if (sample.visibility[a] >= 0.5f && sample.visibility[b] >= 0.5f) {
                val hot = a in highlight && b in highlight   // 위반 부위의 연결선은 붉게 (수정할점 #1)
                val soft = !hot && a in provisional && b in provisional
                drawLine(
                    color = when {
                        hot -> warn.copy(alpha = 0.9f)
                        soft -> provisionalColor.copy(alpha = 0.75f)
                        else -> tint.copy(alpha = 0.55f)
                    },
                    start = point(a),
                    end = point(b),
                    strokeWidth = if (hot) 7f else if (soft) 5f else 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (i in 0 until MP_LANDMARK_COUNT) {
            if (sample.visibility[i] < 0.5f) continue
            val hot = i in highlight
            val soft = !hot && i in provisional
            drawCircle(
                color = when {
                    hot -> warn
                    soft -> provisionalColor
                    else -> Color.White.copy(alpha = 0.85f)
                },
                radius = if (hot) 7f else if (soft) 6f else 4f,
                center = point(i),
            )
        }
    }
}

private suspend fun Context.awaitCameraProviderLive(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
