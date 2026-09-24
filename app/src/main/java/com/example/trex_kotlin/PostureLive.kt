package com.example.trex_kotlin

import com.example.trex_kotlin.posture.FloorFeedback
import com.example.trex_kotlin.posture.FloorFeedbackController
import com.example.trex_kotlin.posture.FloorFeedbackPhase
import com.example.trex_kotlin.posture.PlankGeometry
import com.example.trex_kotlin.posture.PlankAlignmentTracker
import com.example.trex_kotlin.posture.AlignmentSnapshot
import com.example.trex_kotlin.posture.FloorTemporal
import com.example.trex_kotlin.posture.HoldTracker
import com.example.trex_kotlin.posture.PostureAssessment
import com.example.trex_kotlin.posture.AssessmentWindow
import com.example.trex_kotlin.posture.ComparisonMetrics
import com.example.trex_kotlin.posture.ComparisonSnapshot
import com.example.trex_kotlin.posture.ComparisonState
import com.example.trex_kotlin.posture.ComparisonSpeech
import com.example.trex_kotlin.posture.PostureComparisonTracker
import com.example.trex_kotlin.posture.NormalPoseReference
import com.example.trex_kotlin.posture.NormalPoseMatch

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
import androidx.compose.foundation.layout.heightIn
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
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import com.example.trex_kotlin.posture.RepCycle
import com.example.trex_kotlin.posture.RepEngineLog
import com.example.trex_kotlin.posture.RepMetrics
import com.example.trex_kotlin.posture.RepPendingState
import com.example.trex_kotlin.posture.RepRecord
import com.example.trex_kotlin.posture.RepResetEvent
import com.example.trex_kotlin.posture.RepRomTier
import com.example.trex_kotlin.posture.RepUnit
import com.example.trex_kotlin.posture.RepUnitAccumulator
import com.example.trex_kotlin.posture.RepValidation
import com.example.trex_kotlin.posture.SIDE_PAIR_NEXT_HINT
import com.example.trex_kotlin.posture.SIDE_PAIR_UNIT_HINT
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
import com.example.trex_kotlin.posture.ThermalEvent
import com.example.trex_kotlin.posture.ThermalMonitor
import com.example.trex_kotlin.posture.ViewEstimator
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

fun Workout.postureSupported(): Boolean = com.example.trex_kotlin.posture.ExerciseProfiles.forName(name)?.cameraEnabled == true

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

/**
 * 세트당 무효 렙 사유 발화 상한 — 같은 문장을 렙마다 반복하면 잔소리가 되고 자세 지적을 큐 뒤로 민다.
 * (지금 라이브 경로는 ROM 사유를 말하지 않는다. 되살린다면 검증된 기준(`RepRomTier.VALIDATED`)에서만 — spec §58)
 */
private const val MAX_INVALID_CUES = 2

/** 촬영 안내 음성 최소 간격 — 자세를 고치는 데 시간이 걸리므로 자주 말하지 않는다 */
private const val COVERAGE_SPEAK_GAP_MS = 8_000L

/** 세트 경계 발화(요약 + 다음 종목 시작 안내)를 보호하는 시간 — 그동안 코치·커버리지 발화는 flush 대신 큐에 붙는다 */
private const val SET_BOUNDARY_SPEECH_MS = 9_000L

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    setLabel: String = "1 / 1 세트",
    onSkip: () -> Unit = onNext,
    registerFinalizer: ((() -> Unit)?) -> Unit = {},
    repetitions: Int = 0,
    onRepetitions: (Int) -> Unit = {},
    onRepDetected: () -> Unit = {},
    onPartial: () -> Unit = onSkip,
    preparing: Boolean = false,
    /** 렙 검증 모드(spec §61, `RepValidation`) — 숫자 숨김·음성 끔·세트 로그에 좌표. 자동 진행 끄기는 TrexApp 이 한다. */
    validation: Boolean = false,
    onPrepared: () -> Unit = {},
) {
    val c = Trex.c
    KeepScreenOn()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val aihubExercise = postureExerciseMap[workout.name] ?: workout.name
    val profile = com.example.trex_kotlin.posture.ExerciseProfiles.forName(workout.name)
    val preparingRef = rememberUpdatedState(preparing)
    var sampleAt by remember { mutableStateOf(0L) }
    var cameraError by remember { mutableStateOf<String?>(null) }

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
            Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(48.dp))
                    Text("카메라 권한이 필요해요", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 24.dp))
                    Text("자세 비교 없이도 운동을 기록할 수 있어요.", color = c.text2, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("나가기", onClick = onExit, modifier = Modifier.weight(1f))
                    Cta(if (workout.resolvedTarget() is WorkoutTarget.Duration) "시간 측정으로 계속" else "직접 기록으로 계속",
                        onClick = onFallbackToTimer, modifier = Modifier.weight(2f))
                }
            }
        }
        return
    }

    // ---- 실 엔진 파이프라인 (서서 하는 종목 + 바닥 종목 규칙 병합 — PostureLabScreen 과 동일 패턴)
    var ruleSet by remember { mutableStateOf<PostureRuleSet?>(null) }
    var floorExercises by remember { mutableStateOf<Set<String>>(emptySet()) }
    val normalReferenceRef = remember { arrayOf(NormalPoseReference(emptyList())) }
    LaunchedEffect(Unit) {
        normalReferenceRef[0] = runCatching {
            context.assets.open(NormalPoseReference.ASSET).bufferedReader().use { NormalPoseReference.parse(it.lineSequence()) }
        }.getOrDefault(NormalPoseReference(emptyList()))
        runCatching {
            val standing = PostureRuleSet.load(context)
            try {
                val floor = PostureRuleSet.load(context, FLOOR_RULES_ASSET)
                floorExercises = floor.rules.map { it.exercise }.toSet()
                PostureRuleSet("${standing.version}+${floor.version}", standing.generated, standing.rules + floor.rules)
            } catch (_: Throwable) {
                standing
            }
        }.onSuccess { ruleSet = it }
    }
    // 바닥 종목은 중력/3D 피처 대신 2D 평면 피처를 쓴다 (spec §25). 분석 스레드에서 매 프레임 읽으므로 ref 로 전달.
    val isFloorExercise = profile?.floor == true || aihubExercise in floorExercises
    val floorRef = remember { booleanArrayOf(false) }
    // 세트 시작 시점의 검증 모드 — 세트 마감이 지금 값이 아니라 이 값을 쓴다(이 화면은 종목이 바뀌어도 재생성되지 않을 수 있다)
    val validationRef = remember { booleanArrayOf(false) }
    floorRef[0] = isFloorExercise
    val floorExtractor = remember { FloorFeatureExtractor() }
    val holdRef = remember { arrayOfNulls<HoldTracker>(1) }
    var floorMeasurement by remember { mutableStateOf<String?>(null) }
    val floorFeedbackRef = remember { arrayOfNulls<FloorFeedbackController>(1) }
    var floorFeedback by remember { mutableStateOf<FloorFeedback?>(null) }
    val alignmentRef = remember { arrayOfNulls<PlankAlignmentTracker>(1) }
    var alignment by remember { mutableStateOf(AlignmentSnapshot()) }
    val comparisonRef = remember { arrayOfNulls<PostureComparisonTracker>(1) }
    var comparison by remember { mutableStateOf(ComparisonSnapshot()) }
    val comparisonSpeech = remember { ComparisonSpeech() }
    var referenceView by remember { mutableStateOf<String?>(null) }
    val referenceViewRef = remember { arrayOfNulls<String>(1) }
    referenceViewRef[0] = referenceView
    var normalMatches by remember { mutableStateOf<List<NormalPoseMatch>>(emptyList()) }

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
    // ---- 표시 횟수 단위 (사용자 결정 2026-09-24): 런지류는 "왼쪽과 오른쪽을 한 번씩 = 1회" — 카운터 사이클(한 걸음) 둘을 1회로 묶는다.
    //      한쪽만 했을 때는 수가 오르지 않고, 두 쪽을 다 해야 1회가 올라 진행·자동 넘김(spec §42)이 그 수로 간다(세트는 두 쪽을 다 한 뒤 넘어간다).
    //      세트마다 카운터를 만들 때 **그 세트 종목의 단위로** 함께 만든다 — 이 화면은 종목이 바뀌어도 재생성되지 않을 수 있어 지금 값을 쓰면 어긋난다.
    //      repRecords 와 같은 락 안에서 넣고 읽는다. 카운터가 없으면 null. 로그의 렙 기록은 사이클 단위 그대로다(재생 파리티).
    val repUnitRef = remember { arrayOfNulls<RepUnitAccumulator>(1) }
    var repUnit by remember { mutableStateOf(RepUnit.CYCLE) }       // 이 세트의 표시 단위 — 화면 표기용
    var repHalfPending by remember { mutableStateOf(false) }       // 첫 쪽을 마치고 반대쪽을 기다리는 중 — 화면 전용, 말하지 않는다(원칙 #6)
    // 두 수의 합 = 검출 전체(진행·자동 넘김, spec §42) — **표시 단위**다(좌우 짝이면 짝의 수). ROM 은 합을 줄이지 않는다.
    // 어떤 말로 보일지는 세트 리포트의 RepRomTier 가 정한다(spec §58): 검증 기준만 유효/무효·파셜, 미검증은 '참고 · 범위 미달', 기준 없음은 '범위 미판정'.
    // 짝의 ROM 판정은 두 쪽을 합친다 — 한쪽이라도 미달이면 미달, 아니고 한쪽이라도 미판정이면 미판정(RepUnitAccumulator.combine).
    var repCount by remember { mutableIntStateOf(0) }      // ROM 미달로 판정되지 않은 회 (ROM 을 판정하지 않은 회 포함 — '유효' 가 아니다)
    var repInvalid by remember { mutableIntStateOf(0) }    // ROM 기준 미달로 판정된 회
    val onRepLatest = rememberUpdatedState(onRepDetected)
    var deliveredReps by remember { mutableIntStateOf(0) }
    LaunchedEffect(repCount + repInvalid) {
        val detected = repCount + repInvalid
        repeat((detected - deliveredReps).coerceAtLeast(0)) { onRepLatest.value() }
        deliveredReps = detected
    }
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
    // 세트 중 카운터 리셋 사건 (절대 ms) — 세트 로그 단계 0 (spec §58). repRecords 락 안에서 쌓고 마감에서 세트 상대시각으로 바꾼다.
    // afterTMs = 리셋 직전에 카운터가 처리한 마지막 프레임 시각. 프레임 시각은 추론 **전** 에 잡히므로(~60 ms), 누른 시각만으로는
    // 재생이 리셋을 한 프레임 늦게 적용할 수 있다 — 같은 락 안의 순서를 그대로 남긴다.
    val repResets = remember { ArrayList<RepResetEvent>() }
    val lastCounterFrameAt = remember { longArrayOf(0L) }   // 0 = 이 세트에서 카운터가 아직 프레임을 보지 않았다
    // 위반 부위 시각화 (수정할점 #1): 위반 중 규칙의 관절을 스켈레톤에서 붉게 강조
    var violHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // ---- 세션 모드 (spec §29): 코치(초보 기본) / 기록(숙련). 종목별 저장. 정책 레이어만 바꾼다 —
    //      판정·임계값·로그는 두 모드에서 동일하게 계산된다 (모든 사용자 원칙).
    val modeStore = remember { ModeStore(context) }
    var mode by remember(workout.name) { mutableStateOf(modeStore.get(workout.name)) }
    val modeRef = remember { arrayOf(CoachMode.COACH) }
    modeRef[0] = mode
    // TRACK 음성은 모집단 정상/위반 전환이 아니라 직접적인 초기 대비 비교에서만 나온다.

    // ---- 촬영 커버리지 (spec §25b): 규칙이 요구하는 부위가 화면에 없으면 '왜'와 '어떻게'를 안내한다.
    //      판정 자체가 불가능한 상태이므로 자세 코칭보다 우선한다.
    val floorRules = remember(ruleSet, aihubExercise, isFloorExercise) {
        if (isFloorExercise) {
            val active = ruleSet?.rulesFor(aihubExercise, includeBeta = true).orEmpty()
            val template = ruleSet?.rules?.firstOrNull { it.exercise == aihubExercise }
            val feature = com.example.trex_kotlin.posture.RepSignals.byExercise[aihubExercise]?.feature
            active + listOfNotNull(if (template != null && feature != null) template.copy(baseFeature = feature, condition = "동작 측정") else null)
        } else emptyList()
    }
    // 이 종목에서 무엇을 보고 무엇을 못 보는가 (spec §31) — 시작 안내 둘째 문장과 카드 부제가 같은 값을 쓴다.
    // 데드리프트처럼 '척추의 중립' 이 전부 exclude 인 종목은 허리를 말아도 리포트가 "깨끗" 이라 말한다 — 그걸 미리 밝힌다.
    val scope = remember(ruleSet, aihubExercise) { ruleSet?.let { PostureScope.of(it, aihubExercise) } }
    val floorRulesRef = remember { arrayOfNulls<List<PostureRule>>(1) }
    floorRulesRef[0] = floorRules
    var coverage by remember { mutableStateOf(CoverageReport.OK) }
    // 촬영 방향 추정 (spec §33) — 현재 집계 창 기준. 전방 반구가 아니면 규칙이 유보되므로 이유를 화면에 밝힌다
    var viewEst by remember { mutableStateOf<ViewEstimator.Estimate?>(null) }
    // 한 프레임 튀는 것으로 문구가 깜빡이지 않도록, 연속으로 막힐 때만 표시한다
    val coverageStreak = remember { intArrayOf(0) }
    val lastCoverageSpeakAt = remember { longArrayOf(0L) }
    // 음소거는 스피커(세션 스코프)에 남아 다음 운동·완료 화면까지 이어진다
    // TTS 를 못 쓰는 기기에서는 렙 숫자가 통째로 사라진다 — 짧은 톤으로라도 센 것을 알린다
    val repTone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(repTone) { onDispose { runCatching { repTone?.release() } } }
    var muted by remember { mutableStateOf(speech.muted) }
    LaunchedEffect(repetitions) {
        if (repetitions > 0 && !paused && !muted && !validation && repRef[0] != null) speakRep(speech, repTone, repetitions)
    }
    // 검증 모드는 음성을 끈다 — 코칭·숫자 발화가 동작과 템포를 바꾼다(원칙 #6). 사용자가 다시 켤 수는 있다(배너가 알린다).
    // 플래그는 TrexApp 이 파일에서 비동기로 읽는다 — 세트 시작보다 늦게 도착해도 그 세트 로그에 반영되게 여기서도 맞춘다(단계가 바뀔 때만 바뀐다)
    // 켜기 전 음성 상태를 기억했다가 검증 모드가 꺼지거나 화면을 떠날 때 되돌린다 — SpeechCoach 는 세션 사이에 공유되므로 그대로 두면 계속 꺼진다
    val muteBeforeValidation = remember { arrayOfNulls<Boolean>(1) }
    LaunchedEffect(validation) {
        validationRef[0] = validation
        if (validation) {
            if (muteBeforeValidation[0] == null) muteBeforeValidation[0] = muted
            muted = true
        } else muteBeforeValidation[0]?.let { muted = it; muteBeforeValidation[0] = null }
    }
    DisposableEffect(Unit) { onDispose { muteBeforeValidation[0]?.let { speech.muted = it } } }
    LaunchedEffect(muted) {
        speech.muted = muted
        if (muted) speech.stop()
    }
    // 세트 경계 발화(요약 + 다음 종목 시작 안내)가 끝날 때까지 코치·커버리지 발화는 큐에 붙인다(flush 금지) — 요약이 통째로 사라지지 않게
    val boundaryUntil = remember { longArrayOf(0L) }

    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu = true) }
    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val policy = remember { InferencePolicy(sampleIntervalMs = SESSION_SAMPLE_INTERVAL_MS) }
    val lastInferAt = remember { longArrayOf(0L) }
    val thermal = remember { ThermalMonitor(context) }
    val thermalRef = remember { intArrayOf(InferencePolicy.THERMAL_NONE) }
    // 열 상태 변화 이력 (절대 ms, 상태) — 세트 로그 단계 0 (spec §58). 열 상태는 추론 간격을 바꿔 렙당 샘플 수를 바꾼다.
    // 메인 스레드(리스너·마감)에서만 만지지만 락으로 감싼다. API 29 미만이면 비어 있다.
    val thermalHistory = remember { ArrayList<Pair<Long, Int>>() }
    val gravity = remember { GravityTracker(context) }
    DisposableEffect(Unit) {
        gravity.start()
        thermal.start(ContextCompat.getMainExecutor(context)) { s ->
            thermalRef[0] = s
            synchronized(thermalHistory) { if (thermalHistory.lastOrNull()?.second != s) thermalHistory += System.currentTimeMillis() to s }
        }
        onDispose {
            gravity.stop()
            thermal.stop()
            executor.shutdown()
        }
    }
    // 회전해도 Activity 가 유지되므로(configChanges), 화면 회전값은 configuration 변화마다 다시 읽는다.
    // 분석 스레드가 매 프레임 읽으므로 ref 로도 전달한다.
    val configuration = LocalConfiguration.current
    val displayRotation = rememberTrexDisplayRotation()
    val rotationRef = remember { intArrayOf(displayRotation) }
    rotationRef[0] = displayRotation
    // ImageAnalysis 는 바인딩 시점의 회전값을 갖고 있어, 회전 후에는 직접 갱신해야 이미지가 바로 선다.
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    val previewRef = remember { arrayOfNulls<Preview>(1) }
    LaunchedEffect(displayRotation) {
        analysisRef[0]?.targetRotation = displayRotation
        previewRef[0]?.targetRotation = displayRotation
    }
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
    // 어떤 빌드가 센 세트인지 — 폰 검증 전후 빌드를 로그만으로 가른다 (spec §58 단계 0)
    @Suppress("DEPRECATION")
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
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
    val finalized = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    finalizeRef[0] = fin@{ ex: String, label: String, floor: Boolean ->
        if (!finalized.compareAndSet(false, true)) return@fin null
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
            aggregator.reset()
        }
        if (samples.size < MIN_FRAMES_FOR_LOG) return@fin null
        // onset(처음부터/점점/교정됨) 분류는 여기서 — coachRef 는 다음 종목의 LaunchedEffect 에서 새 코치로 바뀐다
        val onset = runCatching { coachRef[0]?.summarize().orEmpty() }.getOrDefault(emptyList())
        val t0 = times.firstOrNull() ?: 0L
        val rc = repRef[0]
        val reps: List<RepRecord>?
        val repTimes: List<Long>?
        val resets: List<RepResetEvent>?
        val pending: RepPendingState?
        val dropped: List<RepCycle>?
        // 표시 단위의 세트 결과 — 리포트(완료 화면·기록)와 로그의 reps.completed 가 화면에 보인 수와 같은 값을 쓴다
        val unitUsed: RepUnit?
        val unitCompleted: Int?
        val unitInvalid: Int?
        val unitTimes: List<Long>?
        val halfPending: Boolean
        synchronized(repRecords) {
            reps = if (rc != null) ArrayList(repRecords) else null
            // 누적기는 카운터와 함께 만든다. 없으면(생기지 않는 조합 — 방어) 사이클 = 1회로 되돌린다.
            val acc = repUnitRef[0]
            unitUsed = if (rc == null) null else acc?.unit ?: RepUnit.CYCLE
            unitCompleted = if (rc == null) null else acc?.completed ?: rc.reps
            unitInvalid = if (rc == null) null else acc?.invalid ?: repRecords.count { it.valid == false }
            unitTimes = if (rc == null) null else acc?.repTimesMs ?: rc.repTimesMs.toList()
            // 세트 끝에 짝을 못 채운 한쪽 — 세지 않는다(진행에도 리포트 수에도 없다). 로그에만 남긴다(reps.half_pending).
            halfPending = rc != null && acc?.pendingHalf == true
            repTimes = rc?.repTimesMs?.toList()   // 분석 스레드의 onFrame 과 같은 락 안에서 복사
            // 세트 로그 단계 0 (spec §58) — 전부 세트 상대시각(프레임 t_ms 와 같은 기준). 첫 프레임 전 사건은 음수로 남는다.
            resets = if (rc != null) repResets.map { e -> RepResetEvent(e.tMs - t0, e.reason, e.afterTMs?.let { it - t0 }) } else null
            // 세지 않은 후보·버린 사이클은 새 코어만 노출한다 — 레거시(지금 앱)는 null. 없는 정보를 빈 값으로 지어내지 않는다.
            pending = rc?.takeIf { it.usesHysteresis }?.pendingAtSetEnd()?.let { p ->
                RepPendingState(
                    p.unconfirmed?.let { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0) },
                    p.inProgress?.let { it.copy(startMs = it.startMs - t0) },
                )
            }
            dropped = rc?.takeIf { it.usesHysteresis }?.droppedReps?.map { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0) }
            repRecords.clear()
            repResets.clear()
        }
        // 열 상태: 첫 프레임 시점 값(그 전 마지막 변화, 없으면 정책이 쓰던 기본값)과 세트 중 변화. 열 상태 API 가 없으면 null.
        val tLast = times.lastOrNull() ?: t0
        val thermalStart: Int?
        val thermalChanges: List<ThermalEvent>?
        synchronized(thermalHistory) {
            thermalStart = if (!thermal.supported) null
                else thermalHistory.lastOrNull { it.first <= t0 }?.second ?: InferencePolicy.THERMAL_NONE
            thermalChanges = if (!thermal.supported) null
                else thermalHistory.filter { it.first > t0 && it.first <= tLast }.map { ThermalEvent(it.first - t0, it.second) }
        }
        val endAt = AssessmentWindow.end(times.lastOrNull() ?: t0, repTimes.orEmpty())
        val startAt = anchorAtRef[0].takeIf { it > 0L } ?: Long.MAX_VALUE
        results = PostureAssessment.evaluate(rs, ex, samples, times, startAt, endAt, reps.orEmpty(), baselineRef[0], MIN_FRAMES_FOR_LOG)
        val measurementLines = results.mapNotNull { it.measurement }.toMutableList()
        measurementLines += comparisonRef[0]?.report(endAt, t0).orEmpty()
        comparisonRef[0]?.snapshot?.values?.take(2)?.forEach { measurementLines += "초반 비교 · ${it.detail}" }
        if (floor && measurementLines.isEmpty()) {
            val observed = samples.indices.lastOrNull { times[it] > startAt && times[it] <= endAt && samples[it].features.isNotEmpty() }?.let { samples[it].features }.orEmpty()
            if (observed.isNotEmpty()) measurementLines += "참고 · " + FloorTemporal.observation(ex, observed).ifEmpty { "검출된 동작 ${reps.orEmpty().size}회 · 자세 미확정" }
        }
        if (endAt < (times.lastOrNull() ?: endAt)) measurementLines += "마지막 검출 뒤 한 주기까지 평가했어요. 종료 구간은 추정값이에요"
        val log = SetLog.build(
            exercise = ex,
            samples = samples,
            results = results,
            assessmentEndTMs = endAt - t0,
            measurements = measurementLines,
            rulesVersion = rs.version,
            model = PoseModel.FULL.label,
            delegate = stats?.delegate ?: "-",
            frontCamera = useFrontCamera,
            sampleIntervalMs = SESSION_SAMPLE_INTERVAL_MS,
            sampleTimesMs = times.map { it - t0 },
            subjectId = subjectId,
            note = "session:$label" + (if (floor) " floor" else "") + " assessment_end_ms=${endAt - t0} " + measurementLines.joinToString(" | "),
            // count·t_ms·렙별 극값·invalid 는 **카운터 사이클** 단위 그대로 — 재생 파리티가 사이클을 센다. 화면 수는 repCompleted.
            repCount = rc?.reps,
            // 프레임 t_ms 와 같은 기준(세트 시작 상대시각)으로 — 첫 로그에서 절대 epoch 로 남던 결함 수정
            repTimesMs = repTimes?.map { it - t0 },
            repSignal = rc?.signal?.feature,
            repInvalid = reps?.count { it.valid == false },
            // 렙별 극값 t 도 세트 상대시각으로 (프레임·repTimesMs 와 같은 기준)
            repRecords = reps?.map { it.copy(tMs = it.tMs - t0) },
            mode = if (modeRef[0] == CoachMode.TRACK) "track" else "coach",
            // 집계 창의 시작 — results 가 이 시점 이후 프레임만 본다는 사실을 로그에 남긴다
            anchorTMs = anchorAtRef[0].takeIf { it > 0L }?.let { it - t0 },
            // spec §58 단계 0 — 폰 검증에서 그 세트를 어떤 카운터·구성·빌드가 셌는지, 언제 사이클을 버렸는지, 열 상태가 어땠는지
            repEngine = rc?.let { RepEngineLog.of(it) },
            repResets = resets,
            repPending = pending,
            repDropped = dropped,
            thermalStart = thermalStart,
            thermalChanges = thermalChanges,
            appVersion = appVersion,
            // 검증 모드 세트만 좌표(xy·w·up)와 이미지 크기를 남긴다(spec §61) — 세트 시작 시점 값
            validation = validationRef[0],
            // 표시 단위(사용자 결정 2026-09-24) — 화면에 보인 수와 세트 끝에 남은 한쪽
            repUnit = unitUsed,
            repCompleted = unitCompleted,
            repHalfPending = halfPending,
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
            workoutName = "$label · $setLabel",
            mode = modeRef[0],
            frames = samples.size,
            baselineActive = baselineRef[0] != null,
            results = results,
            onset = if (modeRef[0] == CoachMode.TRACK || endAt < (times.lastOrNull() ?: endAt)) emptyList() else onset,
            measurements = measurementLines,
            // 렙 카운터 미적용 종목은 null. **표시 단위**의 수 — repsValid = 완료한 회 − ROM 미달 회(ROM 을 판정하지 않은 회 포함), 합 = 화면에 보인
            // 검출 전체(진행·자동 넘김과 같은 수). 이 수를 어떤 말로 보일지는 repRom 단계가 정한다(spec §58) — 검증 기준만 코치 "무효"·기록 "파셜"(§29).
            repsValid = unitCompleted?.let { it - (unitInvalid ?: 0) },
            repsPartial = unitInvalid,
            // 템포도 표시 단위 — 1회 완료 시각의 간격(좌우 짝이면 두 걸음)
            tempoMs = unitTimes?.let { RepMetrics.medianPeriodMs(it) },
            repRom = rc?.let { RepRomTier.of(it.signal) },
            repUnit = unitUsed,
            repHalfPending = halfPending,
        )
    }
    // 세트(운동) 경계 안전망: ✓/✕ 가 이미 마감했으면 멱등으로 null. 마감 없이 화면이 사라질 때(액티비티 종료 등)만
    // 여기서 로그·리포트가 남는다 — 발화는 없다(화면이 이미 없다).
    DisposableEffect(workout.id, preparing) {
        if (preparing) return@DisposableEffect onDispose { }
        val ex = aihubExercise
        val label = workout.name
        val floor = isFloorExercise
        recordedFrames = 0
        registerFinalizer { finalizeRef[0]?.invoke(ex, label, floor)?.let(onSetReport) }
        onDispose {
            registerFinalizer(null)
            finalizeRef[0]?.invoke(ex, label, floor)?.let(onSetReport)
            recordedFrames = 0
        }
    }
    LaunchedEffect(ruleSet, workout.id) {
        val rs = ruleSet ?: return@LaunchedEffect
        // 구형 기준선 TSV에는 변형·촬영 방향·MP 피처 버전이 없다. 다른 촬영의 정답 보정으로 자동 적용하지 않는다.
        // 파일과 개발용 기준선 가이드는 보존하며, 실시간 개인화는 현재 세트의 항목별 observedStart를 사용한다.
        val baselineValues: Map<String, Float>? = null
        baselineRef[0] = baselineValues
        baselineActive = baselineValues != null
        // requireAnchor: 초반 창이 준비 동작(폰 놓고 걸어오기)을 '정상 기준' 으로 삼으면 첫 코칭이 "처음부터…" 오탐이 된다.
        // speakBeta=false: 미보정 규칙은 화면·리포트에 '참고' 로만 남기고 음성은 검증된 규칙만 낸다 (§28 오탐 3건이 전부 베타).
        coachRef[0] = LiveCoach(rs, aihubExercise, baseline = baselineValues, requireAnchor = true, speakBeta = false)
        coachBanner = null   // 이전 종목의 배너·ⓘ 근거 주석이 새 종목에 오귀속되지 않도록
        // 세션 카운터 구성은 RepCounter.forSession 한 곳에 있다 — 재생기(replay-jvm)가 같은 함수를 불러 "앱과 같은 구성" 을 구조로 보장한다.
        // 규칙 JSON 의 kind=rep 설정이 있으면 그 ROM 으로 덮고(검증 표시 끔), 없고 바닥 종목이면 ROM 을 뗀다. 미등록·등척성은 null.
        val repConfig = rs.rulesFor(aihubExercise).firstOrNull { it.kind == "rep" }?.repConfig
        repRef[0] = RepCounter.forSession(aihubExercise, repConfig?.direction, repConfig?.threshold, floor = isFloorExercise)
        // 표시 단위는 이 세트 종목의 프로필에서 — 바닥 종목·프로필 없는 종목은 사이클 단위. 누적기는 아래 락 안에서 카운터와 함께 만든다.
        val unit = RepUnit.forSession(profile, isFloorExercise)
        validationRef[0] = validation
        repUnit = unit
        repHalfPending = false
        repCount = 0
        repInvalid = 0
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
        synchronized(repRecords) {
            repRecords.clear(); repResets.clear(); lastCounterFrameAt[0] = 0L
            repUnitRef[0] = if (repRef[0] != null) RepUnitAccumulator(unit) else null
        }
        comparisonSpeech.clear()
        comparisonRef[0] = PostureComparisonTracker(aihubExercise, (ComparisonMetrics.forExercise(aihubExercise, rs.rules) +
            profile?.let(com.example.trex_kotlin.posture.ExerciseProfiles::metrics).orEmpty()).distinctBy { it.feature },
            observationKind = profile?.kind, variantId = workout.name)
        comparison = ComparisonSnapshot()
        referenceView = null
        normalMatches = emptyList()
        holdRef[0] = ruleSet?.rulesFor(aihubExercise)?.firstOrNull { it.holdConfig != null }?.holdConfig?.let { HoldTracker(it) }
        floorMeasurement = null
        floorFeedbackRef[0] = if (aihubExercise in FloorTemporal.exercises) FloorFeedbackController(aihubExercise, rs.rules) else null
        floorFeedback = null
        alignmentRef[0] = if (aihubExercise == "플랭크") PlankAlignmentTracker(rs.rulesFor(aihubExercise)) else null
        alignment = AlignmentSnapshot()
        floorExtractor.reset()   // 접지선 추정은 세트(운동) 단위 상태
        // 커버리지는 바닥 종목 루프에서만 갱신되므로, 바닥→서서 하는 종목으로 넘어갈 때 여기서 안 풀면 새 종목 내내 코칭이 막힌다
        coverage = CoverageReport.OK
        coverageStreak[0] = 0
        viewEst = null

    }

    LaunchedEffect(useFrontCamera) {
        comparisonRef[0]?.reset()
        alignmentRef[0] = if (aihubExercise == "플랭크") ruleSet?.let { PlankAlignmentTracker(it.rulesFor(aihubExercise)) } else null
        alignment = AlignmentSnapshot()
        comparison = ComparisonSnapshot()
        comparisonSpeech.clear()
        referenceView = null
        normalMatches = emptyList()
    }
    LaunchedEffect(paused) {
        if (paused) {
            // 일시정지 전후를 한 반복으로 잇지 않는다 — 리셋 사건은 세트 로그에 남긴다 (spec §58)
            // 이미 끝낸 한쪽(반쪽)은 버리지 않는다 — 한쪽을 마치고 쉬었다가 반대쪽을 이어 할 수 있다(RepUnitAccumulator.onCounterCycleReset)
            synchronized(repRecords) {
                repRef[0]?.let {
                    it.resetCycle(); repUnitRef[0]?.onCounterCycleReset()
                    repResets += RepResetEvent(System.currentTimeMillis(), "pause", lastCounterFrameAt[0].takeIf { t -> t > 0L })
                }
            }
            alignment = alignmentRef[0]?.add(System.currentTimeMillis(),emptyMap()) ?: AlignmentSnapshot()
            comparisonRef[0]?.unavailable("일시정지 · 처음 기준은 유지하고 진행 중 반복은 다시 측정해요")
            comparison = comparisonRef[0]?.snapshot ?: ComparisonSnapshot()
            comparisonSpeech.clear()
            normalMatches = emptyList()
        }
    }

    LaunchedEffect(aihubExercise,mode,floorFeedback?.phase,floorFeedback?.ruleId,floorFeedback?.message,muted,paused) {
        if (aihubExercise == "플랭크") speech.traceFeedback("plank_ui_state",
            "mode=$mode paused=$paused phase=${floorFeedback?.phase} rule=${floorFeedback?.ruleId} message=${floorFeedback?.message}")
    }

    // FIT_CENTER: 카메라가 보는 **전체**를 보여준다. FILL_CENTER 는 4:3 영상을 긴 화면에 채우느라
    // 좌우(세로 모드) 또는 상하(가로 모드)를 잘라내, 사용자가 실제 분석 범위보다 좁게 보고 프레이밍을 그르쳤다.
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(useFrontCamera) {
        val provider = context.awaitCameraProviderLive()
        cameraError = null
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).setTargetRotation(displayRotation).build()
        previewRef[0] = preview
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
                val wasPreparing = preparingRef.value
                val capturedAt = android.os.SystemClock.elapsedRealtime()
                val up = gravity.gravityDevice
                    ?.let { gravityUpInWorld(it, rotationRef[0], frontRef[0]) }
                    ?: SCREEN_UP
                val s = analyzer.analyze(image, now, up)
                sample = s
                sampleAt = capturedAt
                stats = analyzer.stats()
                // 전환 직전에 추론을 시작한 준비 프레임도 엔진/기준/로그로 들어가지 않는다.
                if (wasPreparing || preparingRef.value) return@setAnalyzer
                if (s.detected) everDetected = true
                if (!s.detected || pausedRef[0]) {
                    alignment = alignmentRef[0]?.add(now,emptyMap()) ?: AlignmentSnapshot()
                    comparisonRef[0]?.unavailable()
                    comparison = comparisonRef[0]?.snapshot ?: ComparisonSnapshot()
                    normalMatches = emptyList()
                    holdRef[0]?.add(now - (recordedTimesMs.firstOrNull() ?: now), null)
                    if (floorRef[0]) {
                        floorMeasurement = "측정 일시 중지 · 몸 전체가 보이게 해주세요"
                        floorFeedback = floorFeedbackRef[0]?.update(now, emptyMap(), null, emptyList(),
                            paused = pausedRef[0], voiceEnabled = speech.ready && !speech.muted && now > boundaryUntil[0])
                        if (modeRef[0] != CoachMode.TRACK) floorFeedback?.speech?.let { speech.speak(it) }
                    }
                }
                if (s.detected && !pausedRef[0] && !finalized.get()) {
                    // 바닥 종목: 중력 기반 3D 피처 대신 2D 평면 피처 (가림 시 피처 단위 유보 포함)
                    val features = if (floorRef[0]) {
                        floorExtractor.computeForExercise(comparisonRef[0]?.exercise.orEmpty(),s.normalizedXy,s.visibility,s.imageWidth,s.imageHeight)
                    } else {
                        s.features
                    }
                    var newFloorRep: RepRecord? = null
                    var comparisonPeakAt: Long? = null
                    var floorObservable = features.isNotEmpty()
                    alignmentRef[0]?.let { alignment = it.add(now,features) }
                    // 촬영 커버리지 — 규칙이 요구하는 부위가 화면에 있는가
                    if (floorRef[0] && alignmentRef[0] == null) {
                        val rep = FloorCoverage.analyze(s.normalizedXy, s.visibility, floorRulesRef[0].orEmpty(), frontRef[0])
                        // 규칙의 관측 범위와 개인 항목의 가시성을 분리한다.
                        if (rep.ok) {
                            coverageStreak[0] = 0
                            if (!coverage.ok) coverage = CoverageReport.OK
                        } else {
                            coverageStreak[0]++
                            if (coverageStreak[0] >= COVERAGE_STREAK) coverage = rep
                        }
                    }
                    // 재보정용 원본 샘플 — 바닥 종목은 규칙이 실제로 쓴 2D 피처를 그대로 남긴다.
                    if (floorRef[0]) {
                        if (anchoredRef[0]) holdRef[0]?.add(now - (recordedTimesMs.firstOrNull() ?: now), features["hip_dev_ankle"])
                        floorMeasurement = if (alignmentRef[0] != null) alignment.items.joinToString(" · ") { it.detail } else holdRef[0]?.snapshot()?.text
                            ?: FloorTemporal.observation(aihubExercise, features).ifEmpty { "동작을 측정하고 있어요" }
                    }
                    // aggregator 도 같은 락 안에서 — 세트 마감의 evaluate/reset 과 겹치면 CME 로 결과가 비어 UNJUDGED 오판정이 난다
                    synchronized(recordedSamples) {
                        aggregator.add(features)
                        recordedSamples.add(if (floorRef[0]) s.withFeatures(features) else s)
                        recordedTimesMs.add(now)
                        recordedFrames = recordedSamples.size
                        // 촬영 방향 (spec §33) — 서서 하는 종목만, 현재 집계 창 기준 (앵커에서 창이 비면 직전 값을 유지)
                        if (!floorRef[0]) ViewEstimator.estimate(aggregator)?.let { viewEst = it }
                    }
                    repRef[0]?.let { rc ->
                        // repTimesMs 갱신은 세트 마감의 복사와 같은 락 안에서
                        val completed = synchronized(repRecords) { lastCounterFrameAt[0] = now; rc.onFrame(now, features[rc.signal.feature]) }
                        if (completed) {
                            comparisonPeakAt = rc.repTimesMs.lastOrNull()
                            // 첫 렙이 끝났다 = 여기부터가 진짜 운동 구간. 초반 창과 **세트 집계**를 여기로 옮긴다 (spec §31).
                            if (coachRef[0]?.anchor() == true) {
                                anchored = true; anchoredRef[0] = true; anchorAtRef[0] = now
                                // 세트 점수·판정의 집계기도 같이 비운다 — 준비 동작이 range/min/max 통계를 통째로 뒤집는다
                                synchronized(recordedSamples) { aggregator.reset() }
                            }
                            // 발표된 사이클 → 렙 기록(사이클 단위 그대로 — 로그·재생 파리티) + 표시 단위의 완료 회(RepUnitAccumulator.onCounterFrame,
                            // 테스트: RepUnitTest·WorkoutSessionTest). 좌우 짝이면 두 쪽을 다 해야 1회가 오른다(사용자 결정 2026-09-24) —
                            // 1회가 완료된 프레임에서만 센다 → onRepDetected(진행·자동 넘김·숫자 발화)도 1회에 한 번. 새 코어는 첫 두 사이클을
                            // 한 프레임에 함께 발표하고, 레거시 경로(지금 앱)는 이 프레임의 한 사이클이다.
                            // ROM 판정값(null = 기준 없음 — 미달이 아니라 수를 줄이지 않지만 '유효' 도 아니다)을 어떤 말로 보일지는 세트 리포트의
                            // RepRomTier 가 정한다(spec §58). 템포는 표시 단위(1회 완료 간격), '반대쪽 차례' 는 화면에만(원칙 #6).
                            val tally = synchronized(repRecords) { RepUnitAccumulator.onCounterFrame(rc, now, repRecords, repUnitRef[0]) }
                            if (floorRef[0]) newFloorRep = tally.records.lastOrNull()
                            repCount += tally.repsNotShort
                            repInvalid += tally.repsShort
                            repTempoMs = tally.tempoMs
                            repHalfPending = tally.halfPending
                            // 빠른 렙 자가진단: 주기가 1.5s 아래면 3.3fps 로는 놓칠 수 있다 (렙당 4샘플 하한 실측) — 움직임(사이클)의 성질이라 사이클 기준
                            repFast = (rc.periodMs ?: Long.MAX_VALUE) < 1_500L
                        }
                    }
                    coachRef[0]?.let { coach ->
                        // 앵커 폴백: 렙 신호가 없는 종목(등척성·컬·레이즈류)이거나 첫 렙이 너무 늦으면 시간으로 앵커한다.
                        if (!coach.isAnchored) {
                            // 기준 시점은 '검출' 이 아니라 '측정 가능' 이다 — PostureAnalyzer 는 가시 관절이 몇 개든
                            // detected=true 를 돌려주므로(관절 11개짜리 프레임도 detected), 검출 기준으로 세면
                            // 사람이 아직 프레임 안에 제대로 없는 동안 폴백이 다 흘러가 버린다.
                            // 실측(§31a): 검출 기준이면 10.0s 에 앵커돼 43.8s 준비 동작이 그대로 집계에 들어갔다.
                            if (detectStartRef[0] == 0L && features.isNotEmpty()) detectStartRef[0] = now
                            if (detectStartRef[0] == 0L) return@let
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
                        violHighlight = if (track || floorRef[0]) emptySet() else RuleHighlight.forViolations(shipStates)
                        provisionalHighlight = if (track || floorRef[0]) emptySet() else RuleHighlight.forViolations(betaStates)
                        // 베타 위반은 말하지 않는 대신 화면에 '참고' 로 남긴다 — 침묵이 "이상 없음" 으로 읽히면 안 된다
                        if (!track && !floorRef[0] && now - provisionalAtRef[0] > PROVISIONAL_NOTE_GAP_MS) {
                            provisionalAtRef[0] = now
                            provisionalNote = betaStates.firstOrNull { it.recent == Verdict.VIOLATION }?.let { st ->
                                val cue = CoachCues.cueFor(st.rule, st.direction ?: Direction.PRIMARY)
                                PostureSetReport.splitCue(if (st.kind == OnsetKind.DRIFT) cue.drift else cue.habit).first
                            }
                        }
                        // 세트 경계 발화(요약·시작 안내)가 나가는 동안은 끊지 않고 뒤에 붙인다
                        val flush = now > boundaryUntil[0]
                        // 커버리지가 막힌 동안에는 자세 지적 대신 촬영 안내를 말한다 (판정 근거가 없으므로) — 바닥 종목만의 상태
                        if (floorRef[0]) {
                            // 바닥 피드백은 아래의 시간·반복 상태기가 한 번만 발화한다.
                        } else if (!coverage.ok) {
                            if (now - lastCoverageSpeakAt[0] > COVERAGE_SPEAK_GAP_MS) {
                                lastCoverageSpeakAt[0] = now
                                speech.speak(coverage.message + ". " + coverage.fix, flush = flush)
                            }
                        } else if (ev != null && !track) {
                            coachBanner = ev
                            speech.speak(ev.message, flush = flush)
                        }
                        // 점수는 리포트와 같은 분모로 — 검증된(ship) 규칙만. 베타를 섞으면 화면과 리포트가 다른 숫자를 말한다.
                        val states = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
                        val ok = states.count { it.recent == Verdict.OK }
                        val bad = states.count { it.recent == Verdict.VIOLATION }
                        if (ok + bad > 0) scoreOk = ok to (ok + bad)
                    }
                    comparisonRef[0]?.let { tracker ->
                        if (floorObservable) comparison = tracker.add(now, features, comparisonPeakAt,
                            anchoredRef[0] || alignmentRef[0] != null || profile?.comparisonOnly == true,
                            referenceEligible = true)
                        else { tracker.unavailable(); comparison = tracker.snapshot }
                        normalMatches = normalReferenceRef[0].compare(tracker.exercise, referenceViewRef[0], tracker.latestSignature, tracker.metrics)
                        if (modeRef[0] == CoachMode.TRACK || profile?.comparisonOnly == true) comparisonSpeech.next(now, comparison,
                            speech.ready && !speech.muted && now > boundaryUntil[0])?.let { speech.speak(it, flush = true) }
                    }
                    if (floorRef[0]) {
                        floorFeedback = floorFeedbackRef[0]?.update(now, features, holdRef[0]?.snapshot(),
                            coachRef[0]?.lastStates.orEmpty(), newFloorRep, anchoredRef[0], floorObservable,
                            mode = modeRef[0], voiceEnabled = modeRef[0] != CoachMode.TRACK && speech.ready && !speech.muted && now > boundaryUntil[0],
                            alignment = alignment.takeIf { alignmentRef[0] != null })
                        if (modeRef[0] != CoachMode.TRACK) floorFeedback?.speech?.let { speech.speak(it, flush = true) }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            android.util.Log.d("TrexCamera", "bind ${workout.id}")
            kotlinx.coroutines.awaitCancellation()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            cameraError = "카메라를 열 수 없어요. 다른 카메라로 전환하거나 직접 기록할 수 있어요."
        } finally {
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
        }
    }

    // 제어판의 실제 경계를 따라 PiP를 확장한다. FIT_CENTER와 같은 CameraX 연결을 유지한다.
    val panelController = remember(workout.id, useFrontCamera, displayRotation) { LivePanelController() }
    var panelVisible by remember(panelController) { mutableStateOf(true) }
    var preparationSkipped by remember(workout.id) { mutableStateOf(true) }
    var countdownEntry by remember(workout.id) { mutableStateOf(false) }
    val currentPanelSample by rememberUpdatedState(sample)
    val currentPanelSampleAt by rememberUpdatedState(sampleAt)
    val keepPanelOpen by rememberUpdatedState(preparing || paused || cameraError != null)
    LaunchedEffect(panelController, preparing) {
        if (preparing) { panelVisible = true; return@LaunchedEffect }
        panelController.beginSession(android.os.SystemClock.elapsedRealtime(), preparationSkipped)
        panelVisible = panelController.visible
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            val scale = if (now - currentPanelSampleAt in 0..900) currentPanelSample.panelBodyScale() else null
            val fullBody = scale != null && profile?.let {
                com.example.trex_kotlin.posture.CaptureFraming.inspect(currentPanelSample, it.capture, it.floor).ready
            } == true
            panelVisible = panelController.update(now, scale, keepPanelOpen, fullBody)
            kotlinx.coroutines.delay(150)
        }
    }
    val panelAnimation = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(preparing, panelVisible) {
        if (preparing) panelAnimation.snapTo(1f)
        else if (countdownEntry) { panelAnimation.snapTo(0f); countdownEntry = false }
        else panelAnimation.animateTo(if (panelVisible) 1f else 0f, tween(280))
    }
    val panelProgress = if (!preparing && countdownEntry) 0f else panelAnimation.value
    fun openPanel() { panelController.reveal(android.os.SystemClock.elapsedRealtime()); panelVisible = true }
    fun closePanel() { if (!paused) { panelController.collapse(android.os.SystemClock.elapsedRealtime()); panelVisible = false } }

    val onCam = Color.White

    val liveMessage = when {
                        paused -> "일시정지"
                        mode == CoachMode.TRACK || profile?.comparisonOnly == true -> comparison.message
                        isFloorExercise -> floorFeedback?.message ?: "옆모습을 화면에 담아 주세요."
                        !coverage.ok -> coverage.message
                        viewEst?.cls?.let { !it.front && it != ViewEstimator.ViewClass.UNKNOWN } == true ->
                            profile?.capture?.placement ?: "전신이 보이도록 자리 잡아 주세요."
                        coachBanner != null -> coachBanner!!.message
                        sample.features.isNotEmpty() -> "움직임을 비교하고 있어요."
                        else -> "전신을 화면에 담아 주세요."
                    }

    val cameraArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (preparing) PreparationFramingOverlay(sample, Modifier.fillMaxSize())
            if (!preparing) LivePoseOverlay(
                sample = sample, mirror = useFrontCamera, tint = c.lime,
                highlight = if (mode == CoachMode.TRACK || paused) emptySet() else if (isFloorExercise) floorFeedback?.landmarks.orEmpty() else violHighlight,
                provisional = if (paused) emptySet() else if (mode == CoachMode.TRACK) comparison.landmarks else provisionalHighlight,
                visibilityCut = if (isFloorExercise) 0.35f else 0.5f,
                plankSide = alignment.visibleSide.takeIf { alignment.placementReady && mode == CoachMode.COACH && !paused },
                modifier = Modifier.fillMaxSize(),
            )
            if (preparing) Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(
                listOf(Color(0xBB10140E), Color.Transparent))).padding(20.dp)) {
                val goal = workout.resolvedTarget()
                Text("$setLabel · 목표 ${goal.amount}${if (goal is WorkoutTarget.Duration) "초" else "회"}", color = Color.White, fontSize = 13.sp)
                Text(workout.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!preparing && !panelVisible) {
                LiveQuickActions(onOpen = { openPanel() }, onPause = onTogglePause,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
    }

    val modeControl: @Composable () -> Unit = {
        CoachModeControl(mode, preparing) { selected ->
            openPanel()
            mode = selected; modeRef[0] = selected
            modeStore.set(workout.name, selected)
            speech.stop(); comparisonSpeech.clear()
            coachBanner = null; provisionalNote = null
            violHighlight = emptySet(); provisionalHighlight = emptySet()
            // 모드를 바꿔도 현재 세트 초반 기준과 누적 횟수는 유지한다.
        }
    }
    val panel: @Composable (Modifier) -> Unit = { mod ->
        if (preparing && profile != null) CapturePreparationPanel(
            profile, sample, sampleAt, paused, useFrontCamera, muted,
            onMute = { muted = !muted }, onCamera = { useFrontCamera = !useFrontCamera },
            speech = speech, onStart = { skipped ->
                preparationSkipped = skipped
                countdownEntry = !skipped
                panelVisible = skipped
                onPrepared()
            }, onExit = onExit, modifier = mod, modeControl = modeControl,
            cameraError = cameraError ?: stats?.error?.let { "몸을 인식할 수 없어요. 직접 기록으로 계속할 수 있어요." }, onFallback = onFallbackToTimer,
        ) else Column(mod) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(targetState = liveMessage,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "coach-cue") { msg ->
                        Text(msg, color = c.text, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    if (workout.resolvedTarget() is WorkoutTarget.Repetitions) {
                        // 좌우 짝 단위는 단위와, 한쪽을 마친 동안 '반대쪽 차례' 를 붙인다 — 화면 전용(쪽을 모르므로 말하지 않는다, 원칙 #6)
                        val autoLabel = listOfNotNull("자동 횟수 · 참고",
                            SIDE_PAIR_UNIT_HINT.takeIf { repUnit == RepUnit.SIDE_PAIR },
                            SIDE_PAIR_NEXT_HINT.takeIf { repUnit == RepUnit.SIDE_PAIR && repHalfPending }).joinToString(" · ")
                        Text(if (repRef[0] == null) "직접 횟수 기록" else if (validation) RepValidation.BANNER else autoLabel, color = c.text2,
                            fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            modeControl()
            WorkoutSessionActions(workout, repetitions, repRef[0] != null, paused,
                onTogglePause, onRepetitions, onPartial, onSkip,
                onExit = {
                    finalizeRef[0]?.invoke(aihubExercise, workout.name, isFloorExercise)?.let(onSetReport)
                    onExit()
                },
                onExpandCamera = { closePanel() },
                directTools = {
                    SessionTool(if (muted) "음성 꺼짐" else "음성 켜짐", if (muted) "음성 안내 켜기" else "음성 안내 끄기",
                        if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        { muted = !muted }, Modifier.weight(1f))
                    SessionTool("카메라", "카메라 전환", Icons.Rounded.Cameraswitch, {
                        useFrontCamera = !useFrontCamera
                        comparisonRef[0]?.reset(); comparison = ComparisonSnapshot(); comparisonSpeech.clear()
                        synchronized(repRecords) {
                            repRef[0]?.let {
                                it.resetCycle(); repUnitRef[0]?.onCounterCycleReset()   // 끝낸 한쪽은 유지 — 일시정지와 같다
                                repResets += RepResetEvent(System.currentTimeMillis(), "camera_switch", lastCounterFrameAt[0].takeIf { t -> t > 0L })
                            }
                        }
                    }, Modifier.weight(1f))
                })
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        PostureAdaptiveLayout(
            preparing = preparing, immersive = !preparing, panelProgress = panelProgress,
            header = {
                if (!preparing) LiveWorkoutHud(workout, repetitions, timeLeft, totalSeconds, setLabel, paused,
                    compact = configuration.screenHeightDp < 500,
                    // 검증 모드는 배너가 먼저 — 켜져 있음을 늘 보인다
                    message = if (validation) RepValidation.BANNER else liveMessage.takeIf { !panelVisible },
                    // 검증 모드는 자동 횟수 숫자를 숨긴다 — 집계자가 앱 숫자에 끌려가지 않게(세는 것·로그는 그대로)
                    hideCount = validation && repRef[0] != null,
                    // 운동 중에는 제어판이 접혀 있어(몰입) 좌우 짝 표기를 HUD 에도 둔다 — 한쪽을 마친 동안 '반대쪽 차례', 아니면 단위
                    countNote = if (repRef[0] != null && repUnit == RepUnit.SIDE_PAIR)
                        (if (repHalfPending) SIDE_PAIR_NEXT_HINT else SIDE_PAIR_UNIT_HINT) else null,
                    countNoteActive = repHalfPending)
            },
            camera = { cameraArea(Modifier.fillMaxSize()) },
            controls = {
                panel(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.bg)
                    .then(if (preparing) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 12.dp, vertical = 10.dp))
            },
        )
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

@Composable
private fun LivePoseOverlay(
    sample: PoseSample,
    mirror: Boolean,
    tint: Color,
    /** 확인할 부위 — 서서 ship 위반, 바닥 지속 변화(참고). */
    highlight: Set<Int> = emptySet(),
    /** 미보정(beta) 규칙 위반 — 노랗게. 말하지 않는 판정이므로 붉은색과 같은 확신을 주면 안 된다 (spec §31). */
    provisional: Set<Int> = emptySet(),
    visibilityCut: Float = 0.5f,
    plankSide: Int? = null,
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
        plankSide?.let { side ->
            val shoulder = if(side == 0) 11 else 12
            val ankle = if(side == 0) 27 else 28
            drawLine(Color(0xFF58D8C7),point(shoulder),point(ankle),strokeWidth=3.dp.toPx(),
                pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(),7.dp.toPx())))
        }
        val provisionalColor = Color(0xFFFFC24B)   // 미보정 규칙 = 호박색. 붉은색과 같은 확신을 주지 않는다
        POSE_CONNECTIONS.forEach { (a, b) ->
            if (sample.visibility[a] >= visibilityCut && sample.visibility[b] >= visibilityCut) {
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
            if (sample.visibility[i] < visibilityCut) continue
            val hot = i in highlight
            val soft = !hot && i in provisional
            if (hot) drawCircle(warn.copy(alpha = 0.22f), radius = 16f, center = point(i))
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
