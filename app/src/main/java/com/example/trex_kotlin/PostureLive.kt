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
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.trex_kotlin.posture.PartStatus
import com.example.trex_kotlin.posture.BodyPart
import com.example.trex_kotlin.posture.OutDirection
import com.example.trex_kotlin.posture.FLOOR_RULES_ASSET
import com.example.trex_kotlin.posture.FloorChain
import com.example.trex_kotlin.posture.FloorCoverage
import com.example.trex_kotlin.posture.FloorFeatureExtractor
import com.example.trex_kotlin.posture.FloorRepSummary
import com.example.trex_kotlin.posture.HoldLog
import com.example.trex_kotlin.posture.HoldSummary
import com.example.trex_kotlin.posture.PairedArmCues
import com.example.trex_kotlin.posture.RejectionCues
import com.example.trex_kotlin.posture.InferencePhase
import com.example.trex_kotlin.posture.InferencePolicy
import com.example.trex_kotlin.posture.LiveCoach
import com.example.trex_kotlin.posture.ModeStore
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.PoseModel
import com.example.trex_kotlin.posture.PoseSample
import com.example.trex_kotlin.posture.PostureAnalyzer
import com.example.trex_kotlin.posture.PostureRule
import com.example.trex_kotlin.posture.RepCounter
import com.example.trex_kotlin.posture.RepCycle
import com.example.trex_kotlin.posture.RepEngineLog
import com.example.trex_kotlin.posture.RepFormEvaluator
import com.example.trex_kotlin.posture.RepFormSpecs
import com.example.trex_kotlin.posture.RepFormSummary
import com.example.trex_kotlin.posture.RepMetrics
import com.example.trex_kotlin.posture.RepPendingState
import com.example.trex_kotlin.posture.RepRecord
import com.example.trex_kotlin.posture.RepRejected
import com.example.trex_kotlin.posture.RomShort
import com.example.trex_kotlin.posture.FormMotion
import com.example.trex_kotlin.posture.MotionAnchor
import com.example.trex_kotlin.posture.MotionKind
import com.example.trex_kotlin.posture.MotionOrigin
import com.example.trex_kotlin.posture.MotionPick
import com.example.trex_kotlin.posture.MotionSpec
import com.example.trex_kotlin.posture.ArmCycle
import com.example.trex_kotlin.posture.RepResetEvent
import com.example.trex_kotlin.posture.RepRomTier
import com.example.trex_kotlin.posture.RepUnit
import com.example.trex_kotlin.posture.RepUnitAccumulator
import com.example.trex_kotlin.posture.RepValidation
import com.example.trex_kotlin.posture.SIDE_PAIR_NEXT_HINT
import com.example.trex_kotlin.posture.SideStepCounter
import com.example.trex_kotlin.posture.SideTallies
import com.example.trex_kotlin.posture.TurnReminder
import com.example.trex_kotlin.posture.StepSide
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
 * 스케줄러(화면 추론 85 ms · 판정 300 ms 격자, 발열 시 감속) → MediaPipe PoseLandmarker(GPU 폴백 CPU) → IMU 중력축 체간 좌표계 피처
 * → rules_mp_v0 규칙 평가 → LiveCoach 가 '처음부터/점점 흐트러짐/교정됨' 을 판별해
 * 화면 카드와 음성으로 안내한다. 시뮬레이션이던 세션 자세 평가의 실제 구현이다.
 */


/** 화면용 추론 간격(§94·§96) — 뼈대·프레이밍이 부드럽게 보이는 주기. 판정은 이 주기를 쓰지 않는다. 실제 주기는 placement.fps 로 확인한다. */
private const val SESSION_INFER_INTERVAL_MS = 85L
/**
 * 판정 격자(§96) — 준비 프레임·피처·규칙·카운터·반복 검사·세트 로그가 받는 프레임 간격. 모집단 캡처(`cadenceMs` 300)·§32 오탐률·§21.10 띠·프레임 수 상수
 * (`liveHoldFrames`·`windowFrames`·`TOP_FRAMES`)가 전부 이 전제라, 추론이 빨라져도 판정은 300 ms 칸마다 첫 프레임만 본다. 로그 `sample_interval_ms`.
 */
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
/** 옆으로 너무 돌아선 회의 안내 간격(§62c 후속 10) — 사선 검사가 전부 유보된 회를 침묵으로 두지 않는다. */
private const val ANGLE_NOTE_GAP_MS = 15_000L
/** 목표를 채운 쪽으로 더 디뎠을 때 반대쪽 안내 간격(§63). */
private const val SIDE_CUE_GAP_MS = 8_000L
/** 쪽·방향 안내를 TRACK 화면에 비교 문장보다 먼저 보이는 시간(§63) — TRACK 화면 문구는 비교 문장이라 formNote 가 보이지 않는다. */
private const val GUIDE_NOTE_MS = 6_000L
/** 반복 검사 위반 부위를 칠해 두는 시간 — 다음 회(컬 1.5~3 s)가 끝나기 전후. */
private const val FORM_HIGHLIGHT_MS = 2_500L
/** 교정 화살표가 통과 없이 남는 시간(docs/LIVE_SCREEN_REDESIGN.md §3.2) — 음성 쿨다운(12 s)과 같은 수. */
private const val MOTION_CUE_MS = 12_000L

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
    onShowGuide: (() -> Unit)? = null,
    /** 플랭크 유지 시계 → 세션 남은 시간 다리(spec §99) — TrexApp 이 소유한다. 없으면 플랭크도 벽시계(종전). */
    holdBridge: HoldBridge? = null,
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
    // 분석 스레드와 공유하는 ref 들을 한 객체로 — remember 44개를 하나로 줄여 이 Composable 메서드를 작게 한다(2026-10-02 ART VerifyError, PostureSupport.kt 주석)
    // 프로필 키는 배치 지문(§91)의 거리·높이 척도에 쓴다 — 홀더에 두어 이 Composable 의 지역 변수를 늘리지 않는다(dex 레지스터, CLAUDE.md)
    val refs = remember { LiveSessionRefs().also { it.subjectHeightCm[0] = TrexStore(context).loadProfile()?.heightCm?.toFloat() ?: 0f } }
    LaunchedEffect(Unit) {
        refs.normalReferenceRef[0] = runCatching {
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
            }.plusRepForm()   // spec §62a 반복별 검사(발 너비·발끝·무릎 바닥) — AIHub 밖 항목
        }.onSuccess { ruleSet = it }
    }
    // 바닥 종목은 중력/3D 피처 대신 2D 평면 피처를 쓴다 (spec §25). 분석 스레드에서 매 프레임 읽으므로 ref 로 전달.
    val isFloorExercise = profile?.floor == true || aihubExercise in floorExercises
    // 세트 시작 시점의 검증 모드 — 세트 마감이 지금 값이 아니라 이 값을 쓴다(이 화면은 종목이 바뀌어도 재생성되지 않을 수 있다)
    refs.floorRef[0] = isFloorExercise
    val floorExtractor = remember { FloorFeatureExtractor() }
    var floorMeasurement by remember { mutableStateOf<String?>(null) }
    var floorFeedback by remember { mutableStateOf<FloorFeedback?>(null) }
    var alignment by remember { mutableStateOf(AlignmentSnapshot()) }
    var comparison by remember { mutableStateOf(ComparisonSnapshot()) }
    val comparisonSpeech = remember { ComparisonSpeech() }
    var referenceView by remember { mutableStateOf<String?>(null) }
    refs.referenceViewRef[0] = referenceView
    var normalMatches by remember { mutableStateOf<List<NormalPoseMatch>>(emptyList()) }

    // ---- 개인 기준선(정상-앵커 재배치, spec §25c/§25d): BaselineGuideScreen 이 수집한 정자세 k세트 중앙값.
    //      바닥 규칙 임계값은 AIHub 채택 뷰 투영에 묶여 있어(뷰 간 플래그율 33%p 요동) 사용자의 실제 폰
    //      시점으로 '위치'를 옮겨야 한다. 재투영 실측: 거치가 정확하면 중립, 방위가 어긋나면 +0.11 복구(보험).
    //      수집만 하고 세션에서 안 쓰면 죽은 기능 — 여기서 소비한다.
    val baselineStore = remember { BaselineStore(context) }
    var baselineActive by remember { mutableStateOf(false) }

    // ---- 자동 렙 카운터 (spec §27): 종목별 렙 신호의 히스테리시스 사이클. 분석 스레드에서 갱신.
    //      등척성(플랭크)·미등록 종목은 null. 카운트는 beta — ±1 오차가 구조적이라 참고 표시.
    // ---- 표시 횟수 단위 (사용자 결정 2026-09-24): 런지류는 "왼쪽과 오른쪽을 한 번씩 = 1회" — 카운터 사이클(한 걸음) 둘을 1회로 묶는다.
    //      한쪽만 했을 때는 수가 오르지 않고, 두 쪽을 다 해야 1회가 올라 진행·자동 넘김(spec §42)이 그 수로 간다(세트는 두 쪽을 다 한 뒤 넘어간다).
    //      세트마다 카운터를 만들 때 **그 세트 종목의 단위로** 함께 만든다 — 이 화면은 종목이 바뀌어도 재생성되지 않을 수 있어 지금 값을 쓰면 어긋난다.
    //      repRecords 와 같은 락 안에서 넣고 읽는다. 카운터가 없으면 null. 로그의 렙 기록은 사이클 단위 그대로다(재생 파리티).
    var repUnit by remember { mutableStateOf(RepUnit.CYCLE) }       // 이 세트의 표시 단위 — 화면 표기용
    var repHalfPending by remember { mutableStateOf(false) }       // 첫 쪽을 마치고 반대쪽을 기다리는 중 — 화면 전용, 말하지 않는다(원칙 #6)
    // 쪽별 카운트(런지 SIDE_EACH, §63 — 사용자 결정 2026-09-26): 왼발 앞·오른발 앞을 따로 세고 한쪽을 다 채우면 반대쪽을 안내한다
    var sideTallies by remember { mutableStateOf<SideTallies?>(null) }
    // 쪽 안내·방향 안내(§63) — TRACK 에서도 보이게 따로 둔다(두 모드 모두의 안내). 몇 초 뒤 지운다
    var guideNote by remember { mutableStateOf<String?>(null) }
    var guideStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(guideStamp) {
        if (guideStamp == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(GUIDE_NOTE_MS)
        guideNote = null
    }
    // 두 수의 합 = 검출 전체(진행·자동 넘김, spec §42) — **표시 단위**다(좌우 짝이면 짝의 수). ROM 은 합을 줄이지 않는다.
    // 어떤 말로 보일지는 세트 리포트의 RepRomTier 가 정한다(spec §58): 검증 기준만 유효/무효·파셜, 미검증은 '참고 · 범위 미달', 기준 없음은 '범위 미판정'.
    // 짝의 ROM 판정은 두 쪽을 합친다 — 한쪽이라도 미달이면 미달, 아니고 한쪽이라도 미판정이면 미판정(RepUnitAccumulator.combine).
    var repCount by remember { mutableIntStateOf(0) }      // ROM 미달로 판정되지 않은 회 (ROM 을 판정하지 않은 회 포함 — '유효' 가 아니다)
    var repInvalid by remember { mutableIntStateOf(0) }    // ROM 기준 미달로 판정된 회
    // 반복별 자세 검사(spec §62a, 설계 §21): ship 검사를 위반한 회는 **목표 진행에 넣지 않는다**(사용자 결정 2026-09-25) — 반복 수 자체는 줄지 않고
    // HUD 에 '반복 N · 정확 M' 으로 보인다. beta 검사는 정확을 깎지 못한다(원칙 #2).
    var repIncorrect by remember { mutableIntStateOf(0) }
    // 본인 기준 비율 ROM 종목(덤벨 컬, spec §62c)은 ROM 미달 회('부분')를 화면 횟수·목표 진행에서 뺀다 — 미검증 절대 ROM 종목은 종전대로 센다
    var repPartialExcluded by remember { mutableStateOf(false) }
    // 준비 단계의 최근 프레임(시각·피처) — 운동 첫 프레임에서 카운터의 서 있는 기준으로 심는다(§89 후속 2). 분석 스레드에서만 만진다
    val prepFrames = remember { ArrayDeque<Pair<Long, Map<String, Float>>>() }
    var formNote by remember { mutableStateOf<String?>(null) }   // 마지막 ship 반복 검사 문장(화면). 정확한 회가 나오면 지운다
    val onRepLatest = rememberUpdatedState(onRepDetected)
    val onRepetitionsLatest = rememberUpdatedState(onRepetitions)
    var deliveredReps by remember { mutableIntStateOf(0) }
    /** 쪽별 카운트: 일시정지 중에 오른 쌍이 있다 — 풀리면 절대값으로 맞춘다. */
    // ---- 세션 모드 (spec §29): 코치(초보 기본) / 기록(숙련). 종목별 저장. 정책 레이어만 바꾼다 —
    //      판정·임계값·로그는 두 모드에서 동일하게 계산된다 (모든 사용자 원칙).
    val modeStore = remember { ModeStore(context) }
    var mode by remember(workout.name) { mutableStateOf(modeStore.get(workout.name)) }
    refs.modeRef[0] = mode
    // 부분 제외도 COACH 만 — TRACK 은 세지 않는 기능 없이 전부 센다(사용자 결정 2026-09-25)
    val partialExcludedNow = repPartialExcluded && mode == CoachMode.COACH
    // 쪽별 카운트 종목은 차감이 아니라 풀에서 다시 센다(사용자 결정 2026-09-26 "차감식으로 하지 말고") — 쌍 = min(왼, 오른), COACH 풀은 차단 걸음 제외
    val repCounted = sideTallies?.of(mode == CoachMode.COACH)?.pairs ?: (repCount + (if (partialExcludedNow) 0 else repInvalid) - repIncorrect)
    LaunchedEffect(repCounted, paused) {
        if (sideTallies != null) {
            // 쪽별 카운트는 쪽마다 목표에서 멈춰 쌍이 목표를 넘지 않는다 — 증가분으로 보내면 일시정지 중 버려진 +1 이 다음 걸음으로 돌아오지 않아
            // 두 쪽이 다 ✓ 인데 세트가 넘어가지 않았다(검토 2026-09-26). 절대값으로 맞춘다: 오른 쌍은 바로, 일시정지 중에 오른 쌍은 풀린 뒤에.
            // 직접 고친 수(횟수 수정)가 더 크면 건드리지 않는다
            if (repCounted > deliveredReps) refs.sideSyncPending[0] = true
            deliveredReps = maxOf(deliveredReps, repCounted)
            if (refs.sideSyncPending[0] && !paused) {
                refs.sideSyncPending[0] = false
                if (repCounted > repetitions) onRepetitionsLatest.value(repCounted)
            }
            return@LaunchedEffect
        }
        repeat((repCounted - deliveredReps).coerceAtLeast(0)) { onRepLatest.value() }
        // 세트 안에서 줄어든 수(거둔 잠정 첫 회 §62c 후속 9·모드 전환)는 되돌리지 않고 이미 전한 수를 쥔다 — 다음 실제 회가 그 자리를 채우고,
        // 모드를 오가도 같은 회를 두 번 전하지 않는다. 세트가 바뀌면 세트 초기화가 0 으로 되돌린다
        deliveredReps = maxOf(deliveredReps, repCounted)
    }
    // 무효 렙 사유 발화 횟수 — 세트당 상한(MAX_INVALID_CUES). 렙마다 같은 말을 반복하면 코칭이 잔소리가 되고,
    // 정작 들어야 할 자세 지적이 큐 뒤로 밀린다.
    // 앵커 폴백 기준시각 — 이 종목에서 사람이 처음 잡힌 때(0 = 아직). 종목 경계에서 리셋한다.
    var anchored by remember { mutableStateOf(false) }
    /** 앵커가 잡힌 세트 상대시각(ms). 로그에 남겨 오프라인 재계산이 같은 창을 쓸 수 있게 한다. 0 = 아직. */
    // 베타(미보정) 위반 — 말하지 않고 화면에만 '참고' 로 남긴다
    var provisionalNote by remember { mutableStateOf<String?>(null) }
    var provisionalHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var repFast by remember { mutableStateOf(false) }
    var repTempoMs by remember { mutableStateOf<Long?>(null) }   // 렙 간격 중앙값 — 기록 모드 계기판 (§29)
    val repRecords = remember { ArrayList<RepRecord>() }         // 렙별 극값 — 세트 로그에 남김 (분석 스레드에서 추가)
    // 세트 중 카운터 리셋 사건 (절대 ms) — 세트 로그 단계 0 (spec §58). repRecords 락 안에서 쌓고 마감에서 세트 상대시각으로 바꾼다.
    // afterTMs = 리셋 직전에 카운터가 처리한 마지막 프레임 시각. 프레임 시각은 추론 **전** 에 잡히므로(~60 ms), 누른 시각만으로는
    // 재생이 리셋을 한 프레임 늦게 적용할 수 있다 — 같은 락 안의 순서를 그대로 남긴다.
    val repResets = remember { ArrayList<RepResetEvent>() }
    // 위반 부위 시각화 (수정할점 #1): 위반 중 규칙의 관절을 스켈레톤에서 붉게 강조
    var violHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 반복별 자세 검사 위반 부위(§62c 후속 4) — 그 회가 끝난 순간부터 FORM_HIGHLIGHT_MS 동안 칠한다(창 규칙과 달리 '지금' 이 아니라 '방금 그 회' 의 판정이다).
    // ship = 붉게, beta = '참고' 색. 다음 회가 깨끗하면 바로 지운다. 스탬프가 바뀔 때마다 타이머를 다시 건다
    var formHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var formProvHighlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var formHighlightStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(formHighlightStamp) {
        if (formHighlightStamp == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(FORM_HIGHLIGHT_MS)
        formHighlight = emptySet(); formProvHighlight = emptySet()
    }
    // ---- 검은 무대(docs/LIVE_SCREEN_REDESIGN.md §2): 세트 시작 뒤 몸이 1 s 보이면 배율을 잠그고, 1.5 s 잃으면 영상으로 돌아간다.
    //      분석 스레드가 프레임마다 갱신한다(sample 과 같은 경로). 사용자 토글은 세션 동안 유지된다
    val stageFit = remember { StageFitController() }
    var stageLock by remember { mutableStateOf<BodyBox?>(null) }
    var personLost by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    // 소리 간소화(§7): 횟수는 숫자 TTS 대신 짧은 틱(자세로 뺀 회는 낮은 틱, 마지막 3회는 이중 틱). 숫자 읽기는 토글 — 바닥 종목은 화면을 못 보므로 기본 켬
    var speakNumbers by remember(workout.id) { mutableStateOf(isFloorExercise) }
    // 호흡 표시(§5) — 카운터의 움직임 방향(-1 바닥 쪽·+1 복귀·0 모름). 분석 스레드가 프레임마다 쓴다. 판정과 무관
    var breathDir by remember { mutableIntStateOf(0) }
    // 교정 화살표(§3) — 한 회에 하나, 음성 문장을 만든 사건이 만든다. 다음 회가 통과하면 지우고, 통과 없이 12 s 가 지나면 지운다(음성 쿨다운과 같은 수)
    var motionCue by remember { mutableStateOf<MotionCue?>(null) }
    LaunchedEffect(motionCue?.startedAt) {
        val ttl = motionCue?.ttlMs ?: return@LaunchedEffect
        kotlinx.coroutines.delay(ttl)   // 원천별 수명(§101) — 플랭크 멈춤은 재개까지(상한 60 s), 나머지 12 s
        motionCue = null
    }
    LaunchedEffect(preparing, workout.id) {
        // 바닥 계열(spec §99): 이 세트의 종목·플랭크 시계를 세트 시작 시점 값으로(준비 단계부터 — 준비 프레임의 누운 기준 시드가 종목을 쓴다)
        refs.floorLive.ensureSet(aihubExercise, isFloorExercise, workout.id, ((workout.resolvedTarget() as? WorkoutTarget.Duration)?.amount ?: 0) * 1000L)
        if (preparing) return@LaunchedEffect
        stageFit.reset(); stageLock = null; personLost = false; motionCue = null
        // WORK 시작 — 플랭크 시계 시작(폴백 20 s 의 기준)·남은 시간 다리 묶기, 바닥 세 종목 범위 문장(30분에 한 번)
        refs.floorLive.startWork(System.currentTimeMillis(), holdBridge, speech, speech.muted || validation)
    }
    // 세트 경과(보조 정보 — 반복 운동의 시간은 끝내는 조건이 아니다). 일시정지 중엔 멈춘다
    var setElapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(preparing, workout.id) { setElapsedSec = 0 }
    LaunchedEffect(preparing, paused, workout.id) {
        if (preparing || paused) return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(1000); setElapsedSec++ }
    }

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
    refs.floorRulesRef[0] = floorRules
    var coverage by remember { mutableStateOf(CoverageReport.OK) }
    // 촬영 방향 추정 (spec §33) — 현재 집계 창 기준. 전방 반구가 아니면 규칙이 유보되므로 이유를 화면에 밝힌다
    var viewEst by remember { mutableStateOf<ViewEstimator.Estimate?>(null) }
    // 한 프레임 튀는 것으로 문구가 깜빡이지 않도록, 연속으로 막힐 때만 표시한다
    // 음소거는 스피커(세션 스코프)에 남아 다음 운동·완료 화면까지 이어진다
    // TTS 를 못 쓰는 기기에서는 렙 숫자가 통째로 사라진다 — 짧은 톤으로라도 센 것을 알린다
    val repTone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(repTone) { onDispose { runCatching { repTone?.release() } } }
    var muted by remember { mutableStateOf(speech.muted) }
    LaunchedEffect(repetitions) {
        // 쪽별 카운트 종목은 걸음마다 쪽과 남은 수를 말한다(아래 분석 루프) — 쌍 숫자는 말하지 않는다
        if (repetitions > 0 && !paused && !muted && !validation && refs.repRef[0] != null && refs.sideCounterRef[0] == null) {
            if (speakNumbers) speakRep(speech, repTone, repetitions)
            else {
                // 틱 하나 = 센 회. 숫자는 화면 큰 글자가 맡는다(§7). 마지막 3회는 이중 틱
                val remaining = (workout.resolvedTarget() as? WorkoutTarget.Repetitions)?.let { it.amount - repetitions } ?: Int.MAX_VALUE
                repTick(repTone, if (remaining in 1..3) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK)
            }
        }
    }
    // 검증 모드는 음성을 끈다 — 코칭·숫자 발화가 동작과 템포를 바꾼다(원칙 #6). 사용자가 다시 켤 수는 있다(배너가 알린다).
    // 플래그는 TrexApp 이 파일에서 비동기로 읽는다 — 세트 시작보다 늦게 도착해도 그 세트 로그에 반영되게 여기서도 맞춘다(단계가 바뀔 때만 바뀐다)
    // 켜기 전 음성 상태를 기억했다가 검증 모드가 꺼지거나 화면을 떠날 때 되돌린다 — SpeechCoach 는 세션 사이에 공유되므로 그대로 두면 계속 꺼진다
    LaunchedEffect(validation) {
        refs.validationRef[0] = validation
        if (validation) {
            if (refs.muteBeforeValidation[0] == null) refs.muteBeforeValidation[0] = muted
            muted = true
        } else refs.muteBeforeValidation[0]?.let { muted = it; refs.muteBeforeValidation[0] = null }
    }
    DisposableEffect(Unit) { onDispose { refs.muteBeforeValidation[0]?.let { speech.muted = it }; refs.floorLive.release() } }
    LaunchedEffect(muted) {
        speech.muted = muted
        if (muted) speech.stop()
    }
    // 세트 경계 발화(요약 + 다음 종목 시작 안내)가 끝날 때까지 코치·커버리지 발화는 큐에 붙인다(flush 금지) — 요약이 통째로 사라지지 않게

    val analyzer = remember { PostureAnalyzer(context, PoseModel.FULL, preferGpu = true) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val policy = remember { InferencePolicy(sampleIntervalMs = SESSION_INFER_INTERVAL_MS) }
    val thermal = remember { ThermalMonitor(context) }
    // 열 상태 변화 이력 (절대 ms, 상태) — 세트 로그 단계 0 (spec §58). 열 상태는 추론 간격을 바꿔 렙당 샘플 수를 바꾼다.
    // 메인 스레드(리스너·마감)에서만 만지지만 락으로 감싼다. API 29 미만이면 비어 있다.
    val thermalHistory = remember { ArrayList<Pair<Long, Int>>() }
    val gravity = remember { GravityTracker(context) }
    DisposableEffect(Unit) {
        gravity.start()
        thermal.start(ContextCompat.getMainExecutor(context)) { s ->
            refs.thermalRef[0] = s
            synchronized(thermalHistory) { if (thermalHistory.lastOrNull()?.second != s) thermalHistory += System.currentTimeMillis() to s }
        }
        onDispose {
            gravity.stop()
            thermal.stop()
            // 분석기 닫기는 분석 스레드에서 — 진행 중 추론 뒤로 줄 세운다. 메인에서 닫으면 첫 프레임의 모델 준비(GPU, 수 초)를 기다리며 화면이 멈췄다(검토 2026-09-26)
            analyzer.markClosed()
            runCatching { executor.execute { analyzer.close() } }.onFailure { analyzer.close() }
            executor.shutdown()
        }
    }
    // 회전해도 Activity 가 유지되므로(configChanges), 화면 회전값은 configuration 변화마다 다시 읽는다.
    // 분석 스레드가 매 프레임 읽으므로 ref 로도 전달한다.
    val configuration = LocalConfiguration.current
    val displayRotation = rememberTrexDisplayRotation()
    refs.rotationRef[0] = displayRotation
    // ImageAnalysis 는 바인딩 시점의 회전값을 갖고 있어, 회전 후에는 직접 갱신해야 이미지가 바로 선다.
    LaunchedEffect(displayRotation) {
        refs.analysisRef[0]?.targetRotation = displayRotation
        refs.previewRef[0]?.targetRotation = displayRotation
    }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var useFrontCamera by remember { mutableStateOf(true) }
    refs.frontRef[0] = useFrontCamera
    refs.pausedRef[0] = paused

    var sample by remember { mutableStateOf(PoseSample.empty()) }
    var stats by remember { mutableStateOf<AnalyzerStats?>(null) }
    var everDetected by remember { mutableStateOf(false) }
    var coachBanner by remember { mutableStateOf<CoachEvent?>(null) }
    // 자세 점수는 **분수**로 보여 준다 — 종목당 검증된 규칙이 2~4개뿐이라 한 건 위반이 33%p 를 깎는다.
    // "67%" 는 성적처럼 읽히지만 실제 의미는 "3가지 중 2가지 정상" 이다.
    var scoreOk by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
    val finalized = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    refs.finalizeRef[0] = fin@{ ex: String, label: String, floor: Boolean ->
        if (!finalized.compareAndSet(false, true)) return@fin null
        // 플랭크 유지 시계(§99) — 사본을 굳히고 남은 시간 다리를 푼다(종료 확인을 취소해도 이 세트는 벽시계로 이어진다 — 분석 루프는 마감 뒤라 시계를 더 부르지 않는다)
        refs.floorLive.finishSet()
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
        // 플랭크는 사람이 거의 안 잡혀도(폰이 벽을 향함 — 가장 심한 촬영 실패) 시계가 돌았으면 최소 리포트·로그를 남긴다 — '카메라 미확인 · 시계 기록' 이 빠지지 않게(Q2)
        if (samples.size < MIN_FRAMES_FOR_LOG && refs.floorLive.finishedHold?.takeIf { floor && it.wallMs > 0L } == null) return@fin null
        // onset(처음부터/점점/교정됨) 분류는 여기서 — refs.coachRef 는 다음 종목의 LaunchedEffect 에서 새 코치로 바뀐다
        val onset = runCatching { refs.coachRef[0]?.summarize().orEmpty() }.getOrDefault(emptyList())
        val t0 = times.firstOrNull() ?: refs.floorLive.finishedHold?.startAt ?: 0L
        val rc = refs.repRef[0]
        val reps: List<RepRecord>?
        val repTimes: List<Long>?
        val resets: List<RepResetEvent>?
        val pending: RepPendingState?
        val dropped: List<RepCycle>?
        val rejected: List<RepRejected>?
        val retracted: List<Long>?
        val identitySwings: List<Float?>?
        val floorTr: FloorSetCopy?
        val armCycles: List<ArmCycle>?
        val formSummary: RepFormSummary?
        // 표시 단위의 세트 결과 — 리포트(완료 화면·기록)와 로그의 reps.completed 가 화면에 보인 수와 같은 값을 쓴다
        val unitUsed: RepUnit?
        val unitCompleted: Int?
        val unitInvalid: Int?
        val unitTimes: List<Long>?
        val halfPending: Boolean
        val sides: SideTallies?
        synchronized(repRecords) {
            reps = if (rc != null) ArrayList(repRecords) else null
            // 누적기는 카운터와 함께 만든다. 없으면(생기지 않는 조합 — 방어) 사이클 = 1회로 되돌린다.
            val acc = refs.repUnitRef[0]
            unitUsed = if (rc == null) null else acc?.unit ?: RepUnit.CYCLE
            unitCompleted = if (rc == null) null else refs.sideCounterRef[0]?.track?.pairs ?: acc?.completed ?: rc.reps
            unitInvalid = if (rc == null) null else acc?.invalid ?: repRecords.count { it.valid == false }
            unitTimes = if (rc == null) null else acc?.repTimesMs ?: rc.repTimesMs.toList()
            // 세트 끝에 짝을 못 채운 한쪽 — 세지 않는다(진행에도 리포트 수에도 없다). 로그에만 남긴다(reps.half_pending).
            halfPending = rc != null && acc?.pendingHalf == true && refs.sideCounterRef[0] == null
            // 쪽별 카운트(§63) — 화면에 보인 쌍. completed 는 TRACK 풀(자세로 뺀 걸음을 빼지 않는다 — 스쿼트와 같은 약속)
            sides = refs.sideCounterRef[0]?.tallies()
            repTimes = rc?.repTimesMs?.toList()   // 분석 스레드의 onFrame 과 같은 락 안에서 복사
            // 세트 로그 단계 0 (spec §58) — 전부 세트 상대시각(프레임 t_ms 와 같은 기준). 첫 프레임 전 사건은 음수로 남는다.
            resets = if (rc != null) repResets.map { e -> RepResetEvent(e.tMs - t0, e.reason, e.afterTMs?.let { it - t0 }) } else null
            // 세지 않은 후보·버린 사이클은 새 코어만 노출한다 — 레거시(지금 앱)는 null. 없는 정보를 빈 값으로 지어내지 않는다.
            pending = rc?.takeIf { it.usesHysteresis || it.legTracker != null || it.floorTracker != null }?.pendingAtSetEnd()?.let { p ->
                RepPendingState(
                    p.unconfirmed?.let { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0) },
                    p.inProgress?.let { it.copy(startMs = it.startMs - t0) },
                )
            }
            dropped = rc?.takeIf { it.usesHysteresis }?.droppedReps?.map { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0) }
            // 반복 판별 게이트(spec §62) — 판별 신호가 있는 종목만 목록(비어 있어도). 없는 종목은 null = 키 없음
            rejected = rc?.takeIf { it.signal.identityFeature != null || it.paired || it.legTracker != null || it.floorTracker != null }?.rejectedReps?.map { it.copy(tMs = it.tMs - t0) }
            retracted = rc?.takeIf { it.paired || it.floorTracker != null }?.retractedReps?.map { it - t0 }
            // 바닥 반복 계열(spec §99) — 기각 상세·센 회 상세·소리 없이 버린 후보·판별 유보(세트 상대시각)와 리포트 요약
            floorTr = rc?.floorTracker?.let { ft ->
                FloorSetCopy(ft.rejectedDetail.map { it.copy(tMs = it.tMs - t0) },
                    ft.repDetail.map { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0, peakMs = it.peakMs - t0) },
                    ft.discarded.map { it.copy(tMs = it.tMs - t0) }, ft.identityAbstain.map { it - t0 },
                    FloorRepSummary.of(ft.profile, unitCompleted ?: ft.completed.size, ft.rejected.map { it.feature }, ft.identityAbstain.size, ft.repDetail))
            }
            identitySwings = rc?.takeIf { it.signal.identityFeature != null }?.identitySwings?.toList()
            armCycles = rc?.takeIf { it.paired }?.armCycles?.map { it.copy(tMs = it.tMs - t0, startMs = it.startMs - t0) }
            // 반복별 자세 검사 요약(§62a) — 같은 락 안에서 굳히고 다음 세트를 위해 비운다
            formSummary = refs.repFormRef[0]?.summary()
            refs.repFormRef[0]?.reset(); refs.rejectedSeenRef[0] = 0
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
        val startAt = refs.anchorAtRef[0].takeIf { it > 0L } ?: Long.MAX_VALUE
        // 반복 창 검사는 정면 기하를 전제한다 — 세트의 추정 방향이 정면(C)이 아니면 유보(방향 불명·미추정은 통과)
        // 반복 검사가 전제하는 뷰는 종목마다 다르다(스쿼트 C, 컬 B/D — §62c). 추정 불가(UNKNOWN)면 게이팅하지 않는다
        val formViews = RepFormSpecs.viewsFor(ex)
        val viewOkForForm = viewEst?.let { it.cls == ViewEstimator.ViewClass.UNKNOWN || it.letter in formViews } ?: true
        val formViewLetter = viewEst?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter
        // 플랭크 유지 시계(spec §99) — 세트 마감의 정렬 재평가는 시계의 HOLD 구간만 본다(라이브와 같은 칸). 사본은 위에서 굳혔다(finishSet)
        val holdSnap = refs.floorLive.finishedHold?.takeIf { floor }
        results = PostureAssessment.evaluate(rs, ex, samples, times, startAt, endAt, reps.orEmpty(), refs.baselineRef[0], MIN_FRAMES_FOR_LOG,
            repForm = formSummary, repFormViewOk = viewOkForForm, repFormViewLetter = formViewLetter, holdSegments = holdSnap?.segments)
        val holdSummary = holdSnap?.let { HoldSummary.of(it, t0) }
        val measurementLines = results.mapNotNull { it.measurement }.toMutableList()
        // 바닥 계열의 시간·횟수 줄(설계 §6 리포트) — 로그 measurements·완료 화면이 같은 줄을 쓴다
        measurementLines.addAll(0, PostureSetReport.floorLines(holdSummary, floorTr?.summary, results.filter { it.rule.kind == "alignment" }))
        formSummary?.let { measurementLines += it.lines() }
        measurementLines += refs.comparisonRef[0]?.report(endAt, t0).orEmpty()
        refs.comparisonRef[0]?.snapshot?.values?.take(2)?.forEach { measurementLines += "초반 비교 · ${it.detail}" }
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
            mode = if (refs.modeRef[0] == CoachMode.TRACK) "track" else "coach",
            // 집계 창의 시작 — results 가 이 시점 이후 프레임만 본다는 사실을 로그에 남긴다
            anchorTMs = refs.anchorAtRef[0].takeIf { it > 0L }?.let { it - t0 },
            // spec §58 단계 0 — 폰 검증에서 그 세트를 어떤 카운터·구성·빌드가 셌는지, 언제 사이클을 버렸는지, 열 상태가 어땠는지
            repEngine = rc?.let { RepEngineLog.of(it) },
            repResets = resets,
            repPending = pending,
            repDropped = dropped,
            repRejected = rejected,
            repRetracted = retracted,
            repIdentitySwings = identitySwings,
            repArmCycles = armCycles,
            repForm = formSummary?.toLog(t0),
            thermalStart = thermalStart,
            thermalChanges = thermalChanges,
            appVersion = appVersion,
            // 검증 모드 세트만 좌표(xy·w·up)와 이미지 크기를 남긴다(spec §61) — 세트 시작 시점 값
            validation = refs.validationRef[0],
            // 좌표는 항상 남긴다(§62a 후속 5) — 검증 모드가 아니라도 측정 결함(발 회전에 흔들리는 3D 발목 등)을 좌표로 가릴 수 있어야 한다
            coordinates = true,
            // 배치 지문(§91) — 피치·롤 중앙값·추론 주기에 렌즈 사양을 더해 거리·높이를 추정한다
            lens = refs.lensRef[0],
            subjectHeightCm = refs.subjectHeightCm[0].takeIf { it > 0f },
            // 표시 단위(사용자 결정 2026-09-24) — 화면에 보인 수와 세트 끝에 남은 한쪽
            repUnit = unitUsed,
            repCompleted = unitCompleted,
            repHalfPending = halfPending,
            repSides = sides,
            // 바닥 계열(spec §99) — 기각 상세·센 회 상세·버린 후보·판별 유보, 쪽 잠금 교체 수, 유지 시계
            repFloorRejected = floorTr?.rejected, repFloorReps = floorTr?.reps, repDiscarded = floorTr?.discarded,
            repIdentityAbstain = floorTr?.abstain,
            floorChainSwitches = if (floor && com.example.trex_kotlin.posture.FloorChain.Kind.of(ex) != null) refs.floorLive.chainSwitches() else null,
            hold = holdSnap?.let { HoldLog.of(it, t0) },
        )
        // 분석 executor 는 화면 종료 시 shutdown 되므로 앱 수명의 기록 스레드에서 쓴다. 실패는 삼키지 않고 feedback 로그에 남긴다
        SetLogStore.writer.execute {
            runCatching {
                logStore.append(log)
                savedSets = logStore.totalSets()
            }.onFailure { speech.traceFeedback("set_log_failed", "${log.setId} ${it.javaClass.simpleName}: ${it.message}") }
        }
        PostureSetReport.build(
            setId = log.setId,
            exercise = ex,
            workoutName = "$label · $setLabel",
            mode = refs.modeRef[0],
            frames = samples.size,
            baselineActive = refs.baselineRef[0] != null,
            results = results,
            onset = if (refs.modeRef[0] == CoachMode.TRACK || endAt < (times.lastOrNull() ?: endAt)) emptyList() else onset,
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
            repSides = sides,
            hold = holdSummary,
            floorReps = floorTr?.summary,
            scopeLine = com.example.trex_kotlin.posture.PostureScope.floorLine(ex).takeIf { floor },
        )
    }
    // 세트(운동) 경계 안전망: ✓/✕ 가 이미 마감했으면 멱등으로 null. 마감 없이 화면이 사라질 때(액티비티 종료 등)만
    // 여기서 로그·리포트가 남는다 — 발화는 없다(화면이 이미 없다).
    DisposableEffect(workout.id, preparing) {
        if (preparing) return@DisposableEffect onDispose { }
        // 새 세트 — 직전 세트의 마감(멱등 표시)을 푼다. 풀지 않으면 같은 화면에서 이어지는 세트의 프레임이 기록되지 않는다(:916)
        finalized.set(false)
        val ex = aihubExercise
        val label = workout.name
        val floor = isFloorExercise
        recordedFrames = 0
        registerFinalizer { refs.finalizeRef[0]?.invoke(ex, label, floor)?.let(onSetReport) }
        onDispose {
            registerFinalizer(null)
            refs.finalizeRef[0]?.invoke(ex, label, floor)?.let(onSetReport)
            recordedFrames = 0
        }
    }
    LaunchedEffect(ruleSet, workout.id) {
        val rs = ruleSet ?: return@LaunchedEffect
        // 구형 기준선 TSV에는 변형·촬영 방향·MP 피처 버전이 없다. 다른 촬영의 정답 보정으로 자동 적용하지 않는다.
        // 파일과 개발용 기준선 가이드는 보존하며, 실시간 개인화는 현재 세트의 항목별 observedStart를 사용한다.
        val baselineValues: Map<String, Float>? = null
        refs.baselineRef[0] = baselineValues
        baselineActive = baselineValues != null
        // requireAnchor: 초반 창이 준비 동작(폰 놓고 걸어오기)을 '정상 기준' 으로 삼으면 첫 코칭이 "처음부터…" 오탐이 된다.
        // speakBeta=false: 미보정 규칙은 화면·리포트에 '참고' 로만 남기고 음성은 검증된 규칙만 낸다 (§28 오탐 3건이 전부 베타).
        refs.coachRef[0] = LiveCoach(rs, aihubExercise, baseline = baselineValues, requireAnchor = true, speakBeta = false)
        coachBanner = null   // 이전 종목의 배너·ⓘ 근거 주석이 새 종목에 오귀속되지 않도록
        // 세션 카운터 구성은 RepCounter.forSession 한 곳에 있다 — 재생기(replay-jvm)가 같은 함수를 불러 "앱과 같은 구성" 을 구조로 보장한다.
        // 규칙 JSON 의 kind=rep 설정이 있으면 그 ROM 으로 덮고(검증 표시 끔), 없고 바닥 종목이면 ROM 을 뗀다. 미등록·등척성은 null.
        val repConfig = rs.rulesFor(aihubExercise).firstOrNull { it.kind == "rep" }?.repConfig
        refs.repRef[0] = RepCounter.forSession(aihubExercise, repConfig?.direction, repConfig?.threshold, floor = isFloorExercise)
        repPartialExcluded = refs.repRef[0]?.signal?.romExcludesShort == true
        // 반복별 자세 검사(§62a·§62c) — 등록부에 있는 종목(바벨 스쿼트·덤벨 컬)만. 카운터의 신호·게이트로 창을 자른다
        refs.repFormRef[0] = refs.repRef[0]?.let { RepFormSpecs.evaluatorFor(aihubExercise, it) }
        refs.rejectedSeenRef[0] = 0
        repIncorrect = 0
        formNote = null
        // 표시 단위는 이 세트 종목의 프로필에서 — 바닥 종목·프로필 없는 종목은 사이클 단위. 누적기는 아래 락 안에서 카운터와 함께 만든다.
        val unit = RepUnit.forSession(profile, isFloorExercise)
        refs.validationRef[0] = validation
        repUnit = unit
        repHalfPending = false
        repCount = 0
        repInvalid = 0
        deliveredReps = 0
        refs.invalidCuesRef[0] = 0
        refs.romShortSpokenRef[0] = false
        refs.angleNoteAtRef[0] = 0L
        refs.detectStartRef[0] = 0L
        anchored = false
        refs.anchoredRef[0] = false
        refs.anchorAtRef[0] = 0L
        scoreOk = null
        provisionalNote = null
        refs.provisionalAtRef[0] = 0L
        provisionalHighlight = emptySet()
        formHighlight = emptySet(); formProvHighlight = emptySet(); formHighlightStamp = 0L
        motionCue = null
        breathDir = 0
        repFast = false
        repTempoMs = null
        synchronized(repRecords) {
            repRecords.clear(); repResets.clear(); refs.lastCounterFrameAt[0] = 0L
            refs.repUnitRef[0] = if (refs.repRef[0] != null) RepUnitAccumulator(unit) else null
            // 쪽별 카운트 — 걸음 쪽을 정하는 검사기가 있을 때만(런지). 목표는 세트 시작 시점 값(이 화면은 종목이 바뀌어도 재생성되지 않는다)
            refs.sideCounterRef[0] = if (unit == RepUnit.SIDE_EACH && refs.repFormRef[0] != null && refs.repRef[0] != null)
                SideStepCounter((workout.resolvedTarget() as? WorkoutTarget.Repetitions)?.amount,
                    strictUnknown = refs.repRef[0]?.legTracker != null) else null
        }
        // 첫 걸음 전부터 쪽별 표기('왼 10 · 오 10', '좌우 각 10회') — 비우면 첫 걸음까지 '0회 / 목표 10회' 로 보여 합계 10걸음으로 읽혔다
        sideTallies = synchronized(repRecords) { refs.sideCounterRef[0]?.tallies() }
        refs.sideCueAtRef[0] = 0L
        refs.sideSyncPending[0] = false
        guideNote = null
        comparisonSpeech.clear()
        refs.comparisonRef[0] = PostureComparisonTracker(aihubExercise, (ComparisonMetrics.forExercise(aihubExercise, rs.rules) +
            profile?.let(com.example.trex_kotlin.posture.ExerciseProfiles::metrics).orEmpty()).distinctBy { it.feature },
            observationKind = profile?.kind, variantId = workout.name)
        comparison = ComparisonSnapshot()
        referenceView = null
        normalMatches = emptyList()
        refs.holdRef[0] = ruleSet?.rulesFor(aihubExercise)?.firstOrNull { it.holdConfig != null }?.holdConfig?.let { HoldTracker(it) }
        floorMeasurement = null
        refs.floorFeedbackRef[0] = if (aihubExercise in FloorTemporal.exercises) FloorFeedbackController(aihubExercise, rs.rules) else null
        floorFeedback = null
        refs.alignmentRef[0] = if (aihubExercise == "플랭크") PlankAlignmentTracker(rs.rulesFor(aihubExercise)) else null
        alignment = AlignmentSnapshot()
        floorExtractor.reset()   // 접지선 추정은 세트(운동) 단위 상태
        refs.floorLive.workExtractor = floorExtractor   // 세트 마감이 쪽 잠금 교체 수를 읽는다(§99)
        // 커버리지는 바닥 종목 루프에서만 갱신되므로, 바닥→서서 하는 종목으로 넘어갈 때 여기서 안 풀면 새 종목 내내 코칭이 막힌다
        coverage = CoverageReport.OK
        refs.coverageStreak[0] = 0
        refs.framingRef[0].reset()
        viewEst = null

    }

    LaunchedEffect(useFrontCamera) {
        refs.comparisonRef[0]?.reset()
        refs.alignmentRef[0] = if (aihubExercise == "플랭크") ruleSet?.let { PlankAlignmentTracker(it.rulesFor(aihubExercise)) } else null
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
                refs.repRef[0]?.let {
                    it.resetCycle(); refs.repUnitRef[0]?.onCounterCycleReset()
                    refs.repFormRef[0]?.takeIf { f -> f.stepSides || f.legProfile != null }?.discardWindow()   // 멈추기 전 프레임이 재개 뒤 걸음 창에 섞이지 않게(§63, 걸음 종목만 — 스쿼트·컬 파리티 유지)
                    repResets += RepResetEvent(System.currentTimeMillis(), "pause", refs.lastCounterFrameAt[0].takeIf { t -> t > 0L })
                }
            }
            alignment = refs.alignmentRef[0]?.add(System.currentTimeMillis(),emptyMap()) ?: AlignmentSnapshot()
            refs.comparisonRef[0]?.unavailable("일시정지 · 처음 기준은 유지하고 진행 중 반복은 다시 측정해요")
            comparison = refs.comparisonRef[0]?.snapshot ?: ComparisonSnapshot()
            comparisonSpeech.clear()
            normalMatches = emptyList()
            refs.floorLive.pause(System.currentTimeMillis())   // 플랭크 시계 — 멈춘 동안은 적립·멈춤·경과 밖(§99)
        } else refs.floorLive.resume(System.currentTimeMillis())
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
        refs.previewRef[0] = preview
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisSelector)
            .setTargetRotation(refs.rotationRef[0])
            .build()
        refs.analysisRef[0] = analysis   // 회전 시 targetRotation 갱신용
        analysis.setAnalyzer(executor) { image ->
            val now = System.currentTimeMillis()
            val phase = if (refs.pausedRef[0]) InferencePhase.IDLE else InferencePhase.RECORDING
            if (!policy.shouldInfer(now, refs.lastInferAt[0], phase, refs.thermalRef[0])) {
                image.close()
                return@setAnalyzer
            }
            refs.lastInferAt[0] = now
            try {
                val wasPreparing = preparingRef.value
                val capturedAt = android.os.SystemClock.elapsedRealtime()
                val up = gravity.gravityDevice
                    ?.let { gravityUpInWorld(it, refs.rotationRef[0], refs.frontRef[0]) }
                    ?: SCREEN_UP
                analyzer.faceEnabled = refs.floorRef[0]   // §101d: 바닥 세 종목만 얼굴 메시(시선)
                val s = analyzer.analyze(image, now, up)
                sample = s
                sampleAt = capturedAt
                stats = analyzer.stats()
                // §98a 접촉 최솟값 — 판정 격자(아래)의 유일한 예외: 크런치의 팔꿈치–무릎은 85 ms 프레임마다 모아 판정 프레임에 `elbow_knee_min` 으로 얹는다(순간 접촉)
                refs.contactRef[0].offer(now, s.features)
                // 무대 배율 잠금·영상 복귀(§2) — 준비 단계부터. "사람이 있다" 는 detected 가 아니라 피처가 계산됐는지로(CLAUDE.md 함정). 바닥 종목은 가시성 cut 이 낮다
                stageLock = stageFit.update(now, s.bodyBox(if (refs.floorRef[0]) 0.35f else 0.5f))
                personLost = stageFit.lost
                // §96 판정 격자 — 85 ms 추론은 화면(뼈대·프레이밍·무대)용이고, 아래(준비 프레임·피처·규칙·카운터·로그)는 300 ms 칸마다 첫 프레임만 받는다
                val judgeBin = now / SESSION_SAMPLE_INTERVAL_MS
                if (judgeBin == refs.judgeBinRef[0]) return@setAnalyzer
                refs.judgeBinRef[0] = judgeBin
                // 전환 직전에 추론을 시작한 준비 프레임도 엔진/기준/로그로 들어가지 않는다.
                // 다만 최근 준비 프레임을 남겨 운동 첫 프레임에서 카운터의 쉬는 자세 기준으로 쓴다(§89 후속 2 — 바닥 종목도, §99)
                if (wasPreparing || preparingRef.value) {
                    refs.contactRef[0].clear()
                    // 바닥 계열 세 종목(§99)은 관측 층 피처를 남겨 운동 첫 프레임에 누운 기준으로 심는다(앉아 있으면 심지 않는다). 다른 바닥 종목은 준비 전용
                    // 추출기의 레거시 피처로 심는다(§99a — 접지선 신호는 standingSeedFrom 이 거른다)
                    (if (!refs.floorRef[0]) s.features.takeIf { it.isNotEmpty() } else refs.floorLive.prepFeatures(s, now) ?: prepFeatures(refs, s, aihubExercise))?.let { pf ->
                        synchronized(prepFrames) { prepFrames.addLast(now to pf); while (prepFrames.size > 12) prepFrames.removeFirst() }
                    }
                    return@setAnalyzer
                }
                // 바닥 계열(§99) — 판정 칸마다(사람이 없어도): 크런치·레그 레이즈의 신호 결측·누운 기준 없음 안내, 플랭크 시계의 '사람 없음' 칸
                if (refs.floorRef[0] && !refs.pausedRef[0] && !finalized.get()) {
                    refs.floorLive.repGuide(now, refs.repRef[0], repRecords, speech, speech.muted || refs.validationRef[0])?.let { guideNote = it; guideStamp = now }
                    if (!s.detected) refs.floorLive.holdFrame(now, null, speech, repTone, speech.muted || refs.validationRef[0])
                }
                if (s.detected) everDetected = true
                if (!s.detected || refs.pausedRef[0]) {
                    refs.contactRef[0].clear()
                    alignment = refs.alignmentRef[0]?.add(now,emptyMap()) ?: AlignmentSnapshot()
                    refs.comparisonRef[0]?.unavailable()
                    comparison = refs.comparisonRef[0]?.snapshot ?: ComparisonSnapshot()
                    normalMatches = emptyList()
                    refs.holdRef[0]?.add(now - (recordedTimesMs.firstOrNull() ?: now), null)
                    if (refs.floorRef[0]) {
                        floorMeasurement = "측정 일시 중지 · 몸 전체가 보이게 해주세요"
                        // 관측 문장 음성은 다른 바닥 종목만 — 바닥 계열 세 종목은 신호 결측 안내·유지 시계가 맡는다(§99). 끊지 않는다(speakLatest — 숫자 읽기를 자르지 않게)
                        floorFeedback = refs.floorFeedbackRef[0]?.update(now, emptyMap(), null, emptyList(),
                            paused = refs.pausedRef[0], voiceEnabled = speech.ready && !speech.muted && now > refs.boundaryUntil[0] && refs.floorLive.legacyVoice)
                        if (refs.modeRef[0] != CoachMode.TRACK) floorFeedback?.speech?.let { speech.speakLatest(it) }
                    }
                }
                if (s.detected && !refs.pausedRef[0] && !finalized.get()) {
                    // 바닥 종목: 중력 기반 3D 피처 대신 2D 평면 피처 (가림 시 피처 단위 유보 포함)
                    val features = if (refs.floorRef[0]) {
                        // §99: 바닥 계열 관측 층(FloorChain)은 원래 중력 up(앱의 up 뒤집힘 보정을 되돌린 값)과 프레임 시각(쪽 잠금 이력)을 받는다 — 지역 변수를 늘리지 않는다(VerifyError)
                        // 종목은 세트 시작 시점 값(§99 — 비교 추적기가 생기기 전엔 "" 라 플랭크 피처가 빠졌다)
                        // §101d: 얼굴 메시 피처(face_*)는 분석기 샘플에 있다 — 바닥 맵에 얹는다(시선·세트 로그)
                        refs.floorLive.withFace(floorExtractor.computeForExercise(refs.floorLive.exercise,s.normalizedXy,s.visibility,s.imageWidth,s.imageHeight,
                            FloorChain.gravityUp(s.up, s.upFromGravity, s.upFlipped), now), s.features)
                    } else {
                        // §98a: 85 ms 접촉 최솟값을 이 판정 프레임의 피처에 얹는다 — 평가기·카운터·세트 로그가 같은 맵을 본다(재생 .fcap 파리티)
                        val contact = refs.contactRef[0].drain()
                        if (contact.isEmpty()) s.features else s.features + contact
                    }
                    var newFloorRep: RepRecord? = null
                    var comparisonPeakAt: Long? = null
                    var floorObservable = features.isNotEmpty()
                    // 플랭크 유지 시계(§99) — 판정 칸마다. 정렬 검사는 시계가 확인한 HOLD 칸만 본다(무릎을 댄 칸의 골반 오독 방지)
                    if (refs.floorRef[0]) refs.floorLive.holdFrame(now, features, speech, repTone, speech.muted || refs.validationRef[0])
                    refs.alignmentRef[0]?.let { alignment = it.add(now, features, refs.floorLive.alignmentGate(features)) }
                    // 플랭크 시선 음성(§100) — HOLD 칸의 고개 항목, COACH 만. 말한 문장은 안내 줄에
                    if (refs.alignmentRef[0] != null) refs.floorLive.gazeFrame(now, alignment, features, speech, speech.muted || refs.validationRef[0] || refs.pausedRef[0],
                        refs.modeRef[0] == CoachMode.COACH)?.let { guideNote = it; guideStamp = now }
                    // 바닥 원천(플랭크 멈춤·시선)의 화살표(§101, 참고색) — 재개·교정은 지운다
                    if (refs.floorRef[0]) { if (refs.floorLive.takeCueClear()) motionCue = null; refs.floorLive.takeCue()?.let { motionCue = MotionCue.of(it, now, soft = true) } }
                    // 촬영 커버리지 — 규칙이 요구하는 부위가 화면에 있는가
                    if (refs.floorRef[0] && refs.alignmentRef[0] == null) {
                        val rep = FloorCoverage.analyze(s.normalizedXy, s.visibility, refs.floorRulesRef[0].orEmpty(), refs.frontRef[0])
                        // 규칙의 관측 범위와 개인 항목의 가시성을 분리한다.
                        if (rep.ok) {
                            refs.coverageStreak[0] = 0
                            if (!coverage.ok) coverage = CoverageReport.OK
                        } else {
                            refs.coverageStreak[0]++
                            if (refs.coverageStreak[0] >= COVERAGE_STREAK) coverage = rep
                        }
                    } else if (!refs.floorRef[0]) coverage = refs.framingRef[0].update(now, s.normalizedXy, features).toCoverage()   // 서서: 세트 중 가로 잘림 감시(§101 StandingFraming)
                    // 재보정용 원본 샘플 — 바닥 종목은 규칙이 실제로 쓴 2D 피처를 그대로 남긴다.
                    if (refs.floorRef[0]) {
                        if (refs.anchoredRef[0]) refs.holdRef[0]?.add(now - (recordedTimesMs.firstOrNull() ?: now), features["hip_dev_ankle"])
                        floorMeasurement = if (refs.alignmentRef[0] != null) alignment.items.joinToString(" · ") { it.detail } else refs.holdRef[0]?.snapshot()?.text
                            ?: FloorTemporal.observation(aihubExercise, features).ifEmpty { "동작을 측정하고 있어요" }
                    }
                    // aggregator 도 같은 락 안에서 — 세트 마감의 evaluate/reset 과 겹치면 CME 로 결과가 비어 UNJUDGED 오판정이 난다
                    synchronized(recordedSamples) {
                        aggregator.add(features)
                        recordedSamples.add(if (features === s.features) s else s.withFeatures(features))
                        recordedTimesMs.add(now)
                        recordedFrames = recordedSamples.size
                        // 촬영 방향 (spec §33) — 서서 하는 종목만, 현재 집계 창 기준 (앵커에서 창이 비면 직전 값을 유지)
                        if (!refs.floorRef[0]) ViewEstimator.estimate(aggregator)?.let { viewEst = it }
                    }
                    refs.repRef[0]?.let { rc ->
                        // repTimesMs 갱신은 세트 마감의 복사와 같은 락 안에서
                        // 판별 신호(spec §62 — 스쿼트 knee_maxside)는 카운트 신호와 같은 프레임 값으로 준다. 없는 종목은 null 이라 종전과 같다.
                        val rf = refs.repFormRef[0]
                        val completed = synchronized(repRecords) {
                            refs.lastCounterFrameAt[0] = now
                            // 다리 사이클 경로(§97): 기준 대비 값(hip_drop)을 더한 프레임을 평가기·카운터가 함께 본다 — 재생기와 같은 순서
                            val judged = rc.legTracker?.annotate(features) ?: rc.floorTracker?.annotate(features) ?: features   // 바닥: 누운 기준 대비 상체 들림(§99, 재생기와 같다)
                            rf?.onFrame(now, judged)   // 카운터보다 먼저 — 이 프레임이 사이클 창에 들어간 뒤 사이클이 끝나야 한다
                            // 운동 첫 프레임: 준비 카운트다운 동안 가만히 있던 자세(서서 하는 종목은 선 자세, 바닥 종목은 누운·엎드린 시작 자세 — §99·§99a)를
                            // 카운터의 기준으로 심는다(§89 후속 2) — 기준이 잡히기 전에 움직인 첫 회가 버려지지 않게. 움직이고 있었거나 준비 프레임이 없으면
                            // 심지 않는다(종전처럼 스스로 잡는다). 바닥 반복 계열은 같은 입구가 누운 기준을 심는다(준비 프레임이 누운 영역일 때만). 준비 전용 바닥 추출기의 접지선도 여기서 비운다
                            val seedFrames = synchronized(prepFrames) { if (prepFrames.isEmpty()) null else prepFrames.toList().also { prepFrames.clear(); refs.prepFloorRef[0].reset() } }
                            if (seedFrames != null) rc.standingSeedFrom(seedFrames, now)?.let { rc.seedStanding(now, it) }
                            // 팔별 경로(덤벨 컬, §62c)는 두 팔 값·기각 피처를 쓴다 — 그 밖은 카운트 신호 + 판별 신호(종전과 같다)
                            val done = rc.onFrameFeatures(now, judged)
                            breathDir = rc.motionDirection   // 호흡 표시(§5) — 위상을 따라간다, 앞서지 않는다
                            if (rc.newlyRetracted) {
                                // 잠정 첫 회를 거뒀다(§62c 후속 9) — 8 s 안에 둘째 회가 없던 한 번의 동작(준비 동작)은 세트의 시작이 아니다. 그 회로 센 수·기록·
                                // 자세 기준을 지운다. 이미 전한 1회(화면·진행)는 되돌리지 않는다 — 다음 실제 회가 그 자리를 채운다(deliveredReps 는 줄지 않는다).
                                // 바닥 반복 계열(§99)은 표시 단위 원장에서도 빼고(리포트·로그 completed), 같은 프레임의 새 기각은 아래에서 말하게 둔다(RepUnitAccumulator.onCounterRetracted)
                                refs.rejectedSeenRef[0] = RepUnitAccumulator.onCounterRetracted(rc, repRecords, refs.repUnitRef[0], refs.rejectedSeenRef[0]); rf?.reset()
                                repCount = 0; repInvalid = 0; repIncorrect = 0
                            }
                            // 판별 게이트가 기각한 사이클은 자기 창을 소비한다(§62a) — 무릎 들기 구간이 다음 스쿼트의 바닥으로 읽히지 않게.
                            // 한 다리·바닥 계열(§97·§99)의 판별 기각 — 세지 않은 동작은 낮은 틱으로 알리고, 이유가 있는 기각은 두 모드 모두 말한다(횟수의 입장 조건이지
                            // 자세 코칭이 아니다 — 침묵하면 카운트가 죽은 줄 안다). 같은 이유는 6 s 에 한 번(바닥은 사유별). 반복 검사 평가기(rf)와 무관하다 — 바닥 종목은
                            // 평가기가 0개라 전에는 이 블록이 통째로 건너뛰어졌다(설계 §4.4)
                            RejectionCues.handle(rc, rf, refs.rejectedSeenRef[0], now, refs.floorLive.rejectGate,
                                silent = speech.muted || refs.validationRef[0] || refs.pausedRef[0]).let { r ->
                                refs.rejectedSeenRef[0] = r.seen
                                if (r.cue?.tick == true) repTick(repTone, ToneGenerator.TONE_PROP_NACK)
                                r.cue?.speech?.let { speech.speakLatest(it, heardKey = r.last?.feature?.let { f -> "rj:$f" }) }
                                // 말한 기각 사유의 화살표(§101) — 쪽은 그 사이클의 움직인 다리, 컬은 잠긴 뷰의 카메라 쪽 팔, 바닥은 참고색
                                if (r.motion != null && refs.modeRef[0] == CoachMode.COACH) motionCue = MotionCue.of(MotionSpec(r.motion, MotionOrigin.REASON, side = r.last?.side,
                                    near = FormMotion.nearSide(rf?.lockedView), lateralOk = MotionSpec.lateralOk(rf?.lockedView)), now, soft = refs.floorRef[0])
                                if (r.last != null && rc.floorTracker != null) refs.floorLive.onRejected(rc.floorTracker.profile, rc.rejectedReps.size, r.last.feature)
                            }
                            // 컬 '한 팔만'(§101) — 같은 팔만 3번 이어졌으면 화면 안내(beta, 음성 승격은 폰 블록 뒤). 지역 변수 없이 기존 상태만
                            if (rc.paired) rc.takeOneArmNotice()?.let { guideNote = PairedArmCues.oneArmNote(it); guideStamp = now }
                            done
                        }
                        if (completed) {
                            comparisonPeakAt = rc.repTimesMs.lastOrNull()
                            // 첫 렙이 끝났다 = 여기부터가 진짜 운동 구간. 초반 창과 **세트 집계**를 여기로 옮긴다 (spec §31).
                            if (refs.coachRef[0]?.anchor() == true) {
                                anchored = true; refs.anchoredRef[0] = true; refs.anchorAtRef[0] = now
                                // 세트 점수·판정의 집계기도 같이 비운다 — 준비 동작이 range/min/max 통계를 통째로 뒤집는다
                                synchronized(recordedSamples) { aggregator.reset() }
                            }
                            // 발표된 사이클 → 렙 기록(사이클 단위 그대로 — 로그·재생 파리티) + 표시 단위의 완료 회(RepUnitAccumulator.onCounterFrame,
                            // 테스트: RepUnitTest·WorkoutSessionTest). 좌우 짝이면 두 쪽을 다 해야 1회가 오른다(사용자 결정 2026-09-24) —
                            // 1회가 완료된 프레임에서만 센다 → onRepDetected(진행·자동 넘김·숫자 발화)도 1회에 한 번. 새 코어는 첫 두 사이클을
                            // 한 프레임에 함께 발표하고, 레거시 경로(지금 앱)는 이 프레임의 한 사이클이다.
                            // ROM 판정값(null = 기준 없음 — 미달이 아니라 수를 줄이지 않지만 '유효' 도 아니다)을 어떤 말로 보일지는 세트 리포트의
                            // RepRomTier 가 정한다(spec §58). 템포는 표시 단위(1회 완료 간격), '반대쪽 차례' 는 화면에만(원칙 #6).
                            val tally = synchronized(repRecords) { RepUnitAccumulator.onCounterFrame(rc, now, repRecords, refs.repUnitRef[0]) }
                            if (refs.floorRef[0]) newFloorRep = tally.records.lastOrNull()
                            // 반복별 자세 검사(§62a) — 이 프레임에 센 사이클마다 창을 닫고 판정한다(사이클 = 1회인 종목만 등록돼 있다)
                            val formReps = if (rf == null) emptyList() else synchronized(repRecords) {
                                tally.records.map { r -> rf.onCycle(r.tMs, r.cycleMin, r.cycleMax, r.cycleStartMs, r.side) }
                            }
                            repCount += tally.repsNotShort
                            repInvalid += tally.repsShort
                            // COACH 만 ship 위반 회를 횟수에서 뺀다(spec §62b, 사용자 결정 2026-09-25). TRACK 은 전부 센다 — 게이트 없음.
                            val gate = refs.modeRef[0] == CoachMode.COACH && !refs.floorRef[0]
                            // 부분(ROM 미달)으로 이미 빠진 회는 자세 위반으로 다시 빼지 않는다 — 같은 회를 두 번 빼면 화면 수가 실제보다 준다(폰 15:34 세트 6·8회)
                            if (gate && refs.sideCounterRef[0] == null) {
                                val excluded = formReps.indices.count { i -> !formReps[i].correct && !(rc.signal.romExcludesShort && tally.records.getOrNull(i)?.valid == false) }
                                repIncorrect += excluded
                                // 자세로 뺀 회 = 낮은 틱(§7) — "이 회는 세지 않았어요" 문장을 대신한다. 화면은 큰 숫자가 멈추고 countNote 가 밝힌다
                                if (excluded > 0 && !speech.muted && !refs.validationRef[0] && !refs.pausedRef[0]) repTick(repTone, ToneGenerator.TONE_PROP_NACK)
                            }
                            // 쪽별 카운트(§63) — 걸음마다 그 걸음의 앞다리 쪽과 차단 여부로 두 풀에 넣는다(모드와 무관하게 둘 다 — 세트 중 모드를 바꿔도 맞게)
                            val sc = refs.sideCounterRef[0]
                            val coachNow = refs.modeRef[0] == CoachMode.COACH
                            val sideEvs = if (sc == null) emptyList() else synchronized(repRecords) { formReps.filter { !it.notStep }.map { r -> sc.offer(r.tMs, r.side, blocked = !r.correct) } }
                            if (sc != null) {
                                sideTallies = synchronized(repRecords) { sc.tallies() }
                                // 센 걸음마다 쪽과 남은 수를 말한다 — 숫자 발화와 같은 대기열(끊지 않음). 쪽을 모르는 걸음은 짧은 톤. 자세로 뺀 걸음은 번호 없음(사유를 말한다)
                                val speakCounts = !speech.muted && !refs.validationRef[0] && !refs.pausedRef[0]
                                for (se in sideEvs) {
                                    val e = se.of(coachNow)
                                    if (!e.counted || !speakCounts) {
                                        // 쪽을 몰라 짝에 넣지 않은 걸음(§96 strictUnknown) — 침묵하면 카운트가 죽은 줄 안다. 번호 없이 짧은 톤만
                                        if (speakCounts && !e.known && !e.counted && !e.blocked && !e.extraOnDone) runCatching { repTone?.startTone(ToneGenerator.TONE_PROP_ACK, 60) }
                                        continue
                                    }
                                    if (!e.known || !speech.ready) { runCatching { repTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }; continue }
                                    val n = synchronized(repRecords) { sc.pool(coachNow).count(e.side) }
                                    speech.speak(when (e.remaining) {
                                        null -> "${e.side.label} $n"
                                        0 -> "${e.side.label} 끝"
                                        else -> "${e.side.label} ${e.remaining}개 남음"
                                    }, flush = false, key = e.side.label)   // 같은 쪽의 최신 숫자만(§101 U4)
                                }
                            }
                            // 틀린 부위를 스켈레톤에 칠한다(§62c 후속 4) — COACH·서서만(TRACK 은 모집단 기준 '틀림' 을 칠하지 않는다, §29)
                            if (gate) formReps.lastOrNull()?.let { rep ->
                                val (red, prov) = RuleHighlight.forRepForm(rep)   // 걸음 검사는 앞다리만(§63), 그 밖은 종전과 같다
                                formHighlight = red; formProvHighlight = prov
                                formHighlightStamp = if (red.isEmpty() && prov.isEmpty()) 0L else now
                            }
                            // 이 회에서 말할 것 하나(§62c 후속 10): **자세 사유(ship·COACH) > 가동 범위 사유**. 전에는 ROM 이 먼저라 팔꿈치를 앞으로 낸 회(손목이 덜
                            // 내려가 ROM 도 걸린다)가 전부 "덜 폈어요" 로 나갔다(오늘 자세 위반 57회 중 19회). 코칭 문장은 speakLatest — 서로 끊지 않고 하나만 보류한다
                            val lastRep = formReps.lastOrNull()
                            val sideEv = sideEvs.map { it.of(coachNow) }.let { es -> es.lastOrNull { it.switchTo != null } ?: es.lastOrNull() }
                            // 목표를 채운 쪽으로 더 디딘 걸음은 자세를 말하지 않는다 — 어차피 세지 않는 걸음이라 "더 깊이" 는 끝난 다리를 고치라는 말이 된다
                            val extraStep = sideEv != null && !sideEv.counted && !sideEv.blocked
                            // speak = 실제로 말하는 경우(COACH·추가 걸음 아님)에만 쿨다운·교정됨 대기를 쓴다(§100)
                            val formEv = if (lastRep == null || rf == null) null else rf.eventFor(lastRep, now, gate = gate, speak = gate && !extraStep)
                            if (lastRep?.correct == true) { formNote = null; if (motionCue?.origin == MotionOrigin.REPFORM) motionCue = null }   // 통과한 회 — 반복 검사 화살표만 지운다(§3.2·§101: 한 다리 계열의 센 회는 beta 뿐이라 늘 correct — 다른 다리의 기각 화살표를 지우면 안 된다)
                            // 부분(ROM 미달)으로 빠진 회의 사유(spec §62c 후속 3) — 손목이 보여 준 끝(덜 올림/덜 폄). 세트당 처음 MAX_INVALID_CUES 번은 교정 문장까지, 그 뒤는 짧게
                            val shortReason = if (gate && rc.signal.romExcludesShort && tally.repsShort > 0)
                                (rc.newlyPublishedShort.lastOrNull { it != null } ?: RomShort.RANGE) else null
                            // 이 회(걸음)에서 할 말은 한 문장으로 이어 한 번에 보낸다(§63 후속 2) — 따로 보내면 뒤의 말이 보류된 앞의 말을 밀어내거나,
                            // 대기열에 붙인 쪽 안내가 보류된 사유보다 먼저 나와 사유가 반대쪽 다리 얘기처럼 들렸다. 순서 = 사유 → 쪽 안내 → 방향 안내 → 출발 알림
                            val say = ArrayList<String>()
                            val screenOnly = ArrayList<String>()
                            refs.heardKeyRef[0] = null; refs.setScopedRef[0] = false
                            if (formEv != null && formEv.ship && gate && !extraStep) {
                                // 음성 문장을 만든 그 사건이 화살표도 만든다(§3.2) — 두 채널이 다른 부위를 가리키지 않게. 쪽(런지 뒷무릎)·뷰(컬 카메라 쪽 팔)를 넘긴다(§101)
                                motionCue = MotionCue.of(formEv.check, formEv.direction, formHighlight, now, lastRep?.side, lastRep?.view)
                                refs.heardKeyRef[0] = "rf:${formEv.check.id}"
                                // 빠진 회는 그 자리에서 이유를 말한다 — 침묵하면 카운트가 죽은 줄 안다. 첫 위반은 교정 문장까지, 같은 검사의 쿨다운(12 s) 안은 짧은 단서(brief).
                                // 2단 검사의 코칭 단계 위반은 회를 빼지 않으므로 그 말을 붙이지 않는다
                                // 소리 간소화(§7): "어디가" 는 화살표가 가리키니 말은 "어떻게"(고치는 말)만. 빠진 회는 낮은 틱이 알린다.
                                // 쿨다운 안 재위반은 짧은 단서. 정책(COACH·ship·한 회 한 문장·speakLatest)은 그대로
                                // 검사별 첫 차단에는 "이 회는 세지 않았어요" 를 붙인다(§101 U2) — 스쿼트는 숫자 음성이 없어 빠진 회를 틱으로만 알았다(10-08 13회)
                                say += (if (formEv.brief) "${formEv.message}." else "${formEv.check.fix}.") + (if (formEv.firstExclusion) " 이 회는 세지 않았어요." else "")
                            } else if (shortReason != null) {
                                say += if (refs.invalidCuesRef[0] < MAX_INVALID_CUES) "${rc.signal.shortCue(shortReason)}. 이 회는 세지 않았어요."
                                    else when (shortReason) {
                                        RomShort.TOP -> "덜 올려서 세지 않았어요."
                                        RomShort.BOTTOM -> "덜 펴서 세지 않았어요."
                                        RomShort.RANGE -> "범위가 부족해 세지 않았어요."
                                    }
                                refs.invalidCuesRef[0]++
                                refs.romShortSpokenRef[0] = true   // 다음 끝까지 한 회가 교정(§100)
                                refs.heardKeyRef[0] = "rom"
                                // 가동 범위 화살표(§101) — 카메라 쪽 손목: 덜 올림 = 위, 덜 폄 = 아래, 모름 = 점
                                motionCue = MotionCue.of(MotionSpec(when (shortReason) {
                                    RomShort.TOP -> FormMotion(MotionAnchor.WRISTS, high = MotionKind.UP, pick = MotionPick.NEAR)
                                    RomShort.BOTTOM -> FormMotion(MotionAnchor.WRISTS, high = MotionKind.DOWN, pick = MotionPick.NEAR)
                                    RomShort.RANGE -> FormMotion.mark(MotionAnchor.WRISTS, pick = MotionPick.NEAR)
                                }, MotionOrigin.ROM, near = FormMotion.nearSide(lastRep?.view)), now)
                            } else if (formEv != null && !formEv.ship && !extraStep) {
                                provisionalNote = formEv.message   // beta 는 화면 '참고' 로만 — 침묵이 "이상 없음" 으로 읽히면 안 된다
                            } else if (gate && !extraStep && rf != null && lastRep != null) {
                                // 교정됨(§100): 말한 위반의 검사가 이 회에서 OK 로 판정됐으면 "좋아요, … 교정됐어요"(위반 후보가 없는 회에서만 — LiveCoach 와 같은 순서).
                                // 가동 범위 미달을 말한 뒤 끝까지 한 회도 교정. 화살표·강조는 지운다
                                rf.recoveryEvent(lastRep, now) { speech.recoverable("rf:$it") }?.also { say += it.message; formHighlight = emptySet(); formProvHighlight = emptySet(); motionCue = null }
                                // 가동 범위 교정은 **판정돼 충족한 회**(valid == true)만 — 판정 없는 회(null, 10-08 컬 22172)에 "교정됐어요" 를 말했다(U0). 들린 지적만(§101 장부)
                                if (say.isEmpty() && refs.romShortSpokenRef[0] && tally.records.lastOrNull()?.valid == true) {
                                    refs.romShortSpokenRef[0] = false
                                    if (speech.recoverable("rom")) { say += "좋아요, 가동 범위가 교정됐어요."; if (motionCue?.origin == MotionOrigin.ROM) motionCue = null }
                                }
                            }
                            // 세지 않은 동작의 이유를 말한 뒤 센 회(§100) — 한 다리·바닥 계열, 두 모드. 지금은 꺼져 있다(RejectCueGate.RECOVERY_ENABLED, U0) — F1 RecoveryContext 뒤 다시 켠다
                            if (rc.cueSource != null) refs.floorLive.rejectGate.takeRecovery { speech.recoverable("rj:$it") }?.let { say += it; if (motionCue?.origin == MotionOrigin.REASON) motionCue = null }
                            // 크런치 시선(§100) — 센 회의 얼굴 방향, COACH 만. 말한 문장은 안내 줄에
                            if (refs.floorRef[0]) refs.floorLive.gazeRep(now, rc, speech, speech.muted || refs.validationRef[0] || refs.pausedRef[0], refs.modeRef[0] == CoachMode.COACH)
                                ?.let { guideNote = it; guideStamp = now }
                            if (refs.floorRef[0]) { if (refs.floorLive.takeCueClear()) motionCue = null; refs.floorLive.takeCue()?.let { motionCue = MotionCue.of(it, now, soft = true) } }
                            // 쪽별 안내(§63) — 한쪽을 다 채운 순간 반대쪽을(쪽마다 한 번), 다 채운 쪽으로 더 디디면 다시 반대쪽을(8 s 에 한 번). 두 모드 모두(세는 방법 안내).
                            // 검증 모드는 쓰지 않는다 — 앱이 센 수를 드러낸다(§61). 쪽을 모르는 걸음에서 나온 안내는 추정이라 화면에만(원칙 #6)
                            if (sideEv != null && !refs.validationRef[0]) {
                                val msg = when {
                                    sideEv.switchTo != null -> "이제 ${sideEv.switchTo.label} 동작을 해 주세요."
                                    sideEv.extraOnDone && now - refs.sideCueAtRef[0] > SIDE_CUE_GAP_MS -> "${sideEv.side.label}은 다 했어요. ${sideEv.side.other.label} 동작을 해 주세요."
                                    else -> null
                                }
                                if (msg != null) {
                                    refs.sideCueAtRef[0] = now; refs.setScopedRef[0] = true   // 세트가 끝나면 틀린 말(§101 — 세트 끝 꼬리에서 뺀다)
                                    if (sideEv.known) say += msg else screenOnly += msg
                                    guideNote = msg; guideStamp = now
                                }
                            }
                            // 세트 중 방향 안내(§63, 런지) — 옆으로 돌아서거나 정면으로 선 걸음이 3번 이어지면 종류마다 세트에서 한 번. 두 모드 모두(촬영 안내)
                            if (rf?.turnReminderSteps != null) synchronized(repRecords) { rf.takeTurnReminder() }?.let { kind ->
                                val msg = when (kind) {
                                    TurnReminder.SIDE -> "옆으로 많이 돌아섰어요. 휴대폰 쪽으로 조금 돌아 45도쯤 비스듬히 서 주세요."
                                    TurnReminder.FRONT -> "정면으로 서면 깊이와 좌우를 볼 수 없어요. 휴대폰에서 45도쯤 비스듬히 서 주세요."
                                }
                                say += msg
                                guideNote = msg; guideStamp = now
                            }
                            // 옆으로 너무 돌아선 회(§62c 후속 10) — 사선 검사가 전부 유보된다. 조용히 넘기면 "봤는데 괜찮았다" 로 읽힌다(원칙 #5). 15 s 에 한 번
                            if (gate && rf?.turnReminderSteps == null && lastRep?.turnedTooFar == true && now - refs.angleNoteAtRef[0] > ANGLE_NOTE_GAP_MS) {
                                refs.angleNoteAtRef[0] = now
                                say += "옆으로 너무 돌아서 이 회는 자세를 못 봤어요. 조금 덜 돌아 주세요."
                            }
                            // 처음부터 틀린 출발(§62c 후속 9) — 본인 기준이 모집단에 거의 없는 값이면 세트에서 한 번 말한다. COACH·서서만, 방금 말한 반복 사건 뒤에 붙인다
                            if (gate && rf != null) synchronized(repRecords) { rf.takeNotice(now) }?.let { ev ->
                                say += ev.message
                                // 처음부터 알림의 화살표(§101) — 같은 칸에 반복 화살표가 없을 때만
                                if (ev.ship && motionCue?.startedAt != now) MotionCue.of(ev.check, ev.direction, RuleHighlight.landmarksFor(ev.check.feature), now, lastRep?.side, lastRep?.view)?.let { motionCue = it.copy(origin = MotionOrigin.NOTICE) }
                            }
                            if (say.isNotEmpty() || screenOnly.isNotEmpty()) formNote = (say + screenOnly).joinToString(" ")
                            // 들림 장부 키(§101): 이 회의 지적(반복 검사·가동 범위) — 교정 문장은 이 문장이 재생을 시작한 뒤에만. 쪽 전환만 있는 문장은 세트 범위(꼬리에서 뺀다)
                            if (say.isNotEmpty()) speech.speakLatest(say.joinToString(" "), heardKey = refs.heardKeyRef[0], setScoped = refs.setScopedRef[0] && refs.heardKeyRef[0] == null && say.size == 1)
                            repTempoMs = tally.tempoMs
                            // 쪽별 카운트는 '반쪽 대기' 가 없다(쪽마다 따로 센다) — 누적기의 두 걸음 짝을 쓰면 홀수 걸음마다 강조색이 켜졌다
                            repHalfPending = tally.halfPending && sc == null
                            // 빠른 렙 자가진단: 주기가 1.5s 아래면 3.3fps 로는 놓칠 수 있다 (렙당 4샘플 하한 실측) — 움직임(사이클)의 성질이라 사이클 기준
                            repFast = (rc.periodMs ?: Long.MAX_VALUE) < 1_500L
                        }
                        // 유지 자세(§62c 후속 6) — 반복 없이 숙인 채 있어도 말한다(스쿼트의 창 규칙 '척추의 중립' 과 같은 역할). COACH·서서만.
                        // 이 프레임에 회가 끝났으면 그 회의 판정이 말한다(한 동작에 한 번)
                        // 카운터가 세지 못한 얕은 걸음(§63, 사용자 결정 "80도 부근까지 굽혀지지 않으면 교정 멘트와 시각 표시") — 창을 바꾸므로 모드와 무관하게 부르고,
                        // 말·강조는 COACH 만. 쿨다운(12 s) 안이면 짧은 단서
                        if (!completed && rf != null && !refs.floorRef[0]) {
                            synchronized(repRecords) { rf.missedDipEvent(now, rc.midCycle, speak = refs.modeRef[0] == CoachMode.COACH) }?.let { (ev, side) ->
                                if (refs.modeRef[0] == CoachMode.COACH) {
                                    val msg = if (ev.brief) "${ev.message}." else "${ev.check.fix}."
                                    if (!speech.muted && !refs.validationRef[0]) repTick(repTone, ToneGenerator.TONE_PROP_NACK)   // 세지 않은 걸음 = 낮은 틱(§7)
                                    formNote = msg
                                    speech.speakLatest(msg)
                                    formHighlight = RuleHighlight.landmarksFor(ev.check.highlight?.let { h -> side?.let { h.replace("{front}", it.key) } ?: h.replace("_{front}", "") } ?: ev.check.feature)
                                    formProvHighlight = emptySet(); formHighlightStamp = now
                                    motionCue = MotionCue.of(ev.check, ev.direction, formHighlight, now, side, rf.lockedView)
                                }
                            }
                        }
                        if (!completed && rf != null && refs.modeRef[0] == CoachMode.COACH && !refs.floorRef[0]) {
                            synchronized(repRecords) { rf.liveEvent(now) }?.let { ev ->
                                formNote = ev.message
                                speech.speakLatest(ev.message)
                                formHighlight = RuleHighlight.landmarksFor(ev.check.feature); formProvHighlight = emptySet(); formHighlightStamp = now
                                motionCue = MotionCue.of(ev.check, ev.direction, formHighlight, now)
                            }
                        }
                    }
                    refs.coachRef[0]?.let { coach ->
                        // 앵커 폴백: 렙 신호가 없는 종목(등척성·컬·레이즈류)이거나 첫 렙이 너무 늦으면 시간으로 앵커한다.
                        if (!coach.isAnchored) {
                            // 기준 시점은 '검출' 이 아니라 '측정 가능' 이다 — PostureAnalyzer 는 가시 관절이 몇 개든
                            // detected=true 를 돌려주므로(관절 11개짜리 프레임도 detected), 검출 기준으로 세면
                            // 사람이 아직 프레임 안에 제대로 없는 동안 폴백이 다 흘러가 버린다.
                            // 실측(§31a): 검출 기준이면 10.0s 에 앵커돼 43.8s 준비 동작이 그대로 집계에 들어갔다.
                            if (refs.detectStartRef[0] == 0L && features.isNotEmpty()) refs.detectStartRef[0] = now
                            if (refs.detectStartRef[0] == 0L) return@let
                            val wait = if (refs.repRef[0] == null) ANCHOR_FALLBACK_NO_COUNTER_MS else ANCHOR_FALLBACK_WITH_COUNTER_MS
                            if (now - refs.detectStartRef[0] >= wait && coach.anchor()) {
                                anchored = true; refs.anchoredRef[0] = true; refs.anchorAtRef[0] = now
                                synchronized(recordedSamples) { aggregator.reset() }
                            }
                        }
                        coach.onFrame(features)
                        val ev = coach.evaluate(now)
                        val track = refs.modeRef[0] == CoachMode.TRACK
                        // 강조를 두 갈래로: 검증된(ship) 위반만 붉게, 미보정(beta)은 '참고' 색 — 같은 붉은색이면
                        // 말하지 않기로 한 규칙이 화면에서는 확신처럼 보인다.
                        val shipStates = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
                        val betaStates = coach.lastStates.filter { it.rule.status == RuleStatus.BETA }
                        // 기록 모드: 위반 강조 없음 — 모집단 임계 기준 "틀림" 표시는 스타일을 오판할 수 있다 (§29)
                        violHighlight = if (track || refs.floorRef[0]) emptySet() else RuleHighlight.forViolations(shipStates)
                        provisionalHighlight = if (track || refs.floorRef[0]) emptySet() else RuleHighlight.forViolations(betaStates)
                        // 베타 위반은 말하지 않는 대신 화면에 '참고' 로 남긴다 — 침묵이 "이상 없음" 으로 읽히면 안 된다
                        if (!track && !refs.floorRef[0] && now - refs.provisionalAtRef[0] > PROVISIONAL_NOTE_GAP_MS) {
                            refs.provisionalAtRef[0] = now
                            provisionalNote = betaStates.firstOrNull { it.recent == Verdict.VIOLATION }?.let { st ->
                                val cue = CoachCues.cueFor(st.rule, st.direction ?: Direction.PRIMARY)
                                PostureSetReport.splitCue(when (st.kind) { OnsetKind.DRIFT -> cue.drift; OnsetKind.HABIT -> cue.habit; else -> cue.current }).first
                            }
                        }
                        // 세트 경계 발화(요약·시작 안내)가 나가는 동안은 끊지 않고 뒤에 붙인다
                        val flush = now > refs.boundaryUntil[0]
                        // 커버리지가 막힌 동안에는 자세 지적 대신 촬영 안내를 말한다 (판정 근거가 없으므로) — 바닥 종목만의 상태
                        if (refs.floorRef[0]) {
                            // 바닥 피드백은 아래의 시간·반복 상태기가 한 번만 발화한다.
                        } else if (!coverage.ok) {
                            // 서서 하는 종목의 세트 중 잘림(§101 StandingFraming) — 15 s 간격·세트당 3회, 끊지 않고(쪽별 카운트 문장을 자르지 않게) 두 모드 화면에
                            refs.framingRef[0].takeSpeech(now)?.let { speech.speakLatest(it); guideNote = it; guideStamp = now }
                        } else if (ev != null && !track) {
                            coachBanner = ev
                            speech.speak(ev.message, flush = flush)
                            // 세트 창 규칙(척추 중립 등)의 화살표 — 교정됨이면 지운다. 그릴 수 없는 규칙은 문장만(§3.1)
                            if (ev.kind == com.example.trex_kotlin.posture.OnsetKind.RECOVERED) motionCue = null
                            else MotionCue.ofWindow(ev, now)?.let { motionCue = it }
                        }
                        // 점수는 리포트와 같은 분모로 — 검증된(ship) 규칙만. 베타를 섞으면 화면과 리포트가 다른 숫자를 말한다.
                        val states = coach.lastStates.filter { it.rule.status != RuleStatus.BETA }
                        val ok = states.count { it.recent == Verdict.OK }
                        val bad = states.count { it.recent == Verdict.VIOLATION }
                        if (ok + bad > 0) scoreOk = ok to (ok + bad)
                    }
                    refs.comparisonRef[0]?.let { tracker ->
                        if (floorObservable) comparison = tracker.add(now, features, comparisonPeakAt,
                            refs.anchoredRef[0] || refs.alignmentRef[0] != null || profile?.comparisonOnly == true,
                            referenceEligible = true)
                        else { tracker.unavailable(); comparison = tracker.snapshot }
                        normalMatches = refs.normalReferenceRef[0].compare(tracker.exercise, refs.referenceViewRef[0], tracker.latestSignature, tracker.metrics)
                        if (refs.modeRef[0] == CoachMode.TRACK || profile?.comparisonOnly == true) comparisonSpeech.next(now, comparison,
                            speech.ready && !speech.muted && now > refs.boundaryUntil[0])?.let {
                                // 쪽별 카운트 종목은 걸음마다 쪽·남은 수를 대기열로 말한다 — 비교 문장이 끊으면(flush) 같은 프레임의 수와 쪽 안내가 사라졌다
                                // 바닥 종목도 끊지 않는다(§99) — 숫자 읽기가 바닥 기본값이라 비교 문장이 그 수를 잘랐다(설계 L1275)
                                if (refs.sideCounterRef[0] != null || refs.floorRef[0]) speech.speakLatest(it) else speech.speak(it, flush = true)
                            }
                    }
                    if (refs.floorRef[0]) {
                        floorFeedback = refs.floorFeedbackRef[0]?.update(now, features, refs.holdRef[0]?.snapshot(),
                            refs.coachRef[0]?.lastStates.orEmpty(), newFloorRep, refs.anchoredRef[0], floorObservable,
                            mode = refs.modeRef[0], voiceEnabled = refs.modeRef[0] != CoachMode.TRACK && speech.ready && !speech.muted && now > refs.boundaryUntil[0] && refs.floorLive.legacyVoice,
                            alignment = alignment.takeIf { refs.alignmentRef[0] != null })
                        // 바닥 피드백 음성은 관측 문장뿐(beta 는 말하지 않는다 — Q1). 끊지 않는다(§99)
                        if (refs.modeRef[0] != CoachMode.TRACK) floorFeedback?.speech?.let { speech.speakLatest(it) }
                        // beta 확인 필요(정렬 이탈 등)는 '참고' 칩으로만(원칙 #2) — 큰 교정 줄에 올리지 않는다
                        provisionalNote = floorFeedback?.takeIf { it.phase == FloorFeedbackPhase.ATTENTION }?.message
                    }
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            // 렌즈 사양은 세트 로그의 배치 지문(§91)에 들어간다 — 카메라를 바꾸면 다시 읽는다
            refs.lensRef[0] = readLensInfo(provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis))
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
                com.example.trex_kotlin.posture.CaptureFraming.inspect(currentPanelSample, it.capture, it.floor, it.framingRegion, it.lateralReach).ready
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

    // 화면 글자는 교정·촬영 안내만(§4) — 상태 문구("움직임을 비교하고 있어요")는 쓰지 않는다. null = 띄울 말이 없다
    val liveMessage: String? = when {
                        paused -> "일시정지"
                        // 쪽·방향 안내는 두 모드 모두의 안내다 — TRACK 은 아래가 비교 문장이라 formNote 가 안 보여 따로 먼저 보인다(§63)
                        mode == CoachMode.TRACK && guideNote != null -> guideNote!!
                        mode == CoachMode.TRACK || profile?.comparisonOnly == true -> comparison.message
                        isFloorExercise -> floorLiveMessage(guideNote, floorFeedback)
                        !coverage.ok -> coverage.message
                        // 런지는 세트 중 방향 안내(걸음 검사기)가 배치 문구를 맡는다 — 옆 뷰에서도 판정하므로 교정 문장을 가리지 않는다(§63)
                        refs.repFormRef[0]?.turnReminderSteps == null && viewEst?.cls?.let { !it.front && it != ViewEstimator.ViewClass.UNKNOWN } == true ->
                            profile?.capture?.placement ?: "전신이 보이도록 자리 잡아 주세요."
                        formNote != null -> formNote!!
                        coachBanner != null -> coachBanner!!.message
                        sample.features.isNotEmpty() -> null
                        else -> "전신을 화면에 담아 주세요."
                    }

    val cameraArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // 검은 무대(§2) — 준비 단계는 영상(자리 잡기는 영상이 빠르다), 세트 중엔 뼈대만. 사람을 잃으면·사용자가 켜면 영상이 돌아온다.
            // 영상 유스케이스는 끊지 않는다(일부 기기에서 ImageAnalysis 단독 바인딩이 프레임률을 바꾼다, §8) — 검은 캔버스로 덮기만 한다
            // 준비 단계도 검은 무대다(사용자 결정 2026-10-02) — 자리 잡기는 프레임 테두리·점선 관절·잘린 변 발광이 안내하고, 몸이 없으면 영상이 돌아온다.
            // 배율 잠금은 세트부터(준비 중 잠그면 세트 시작에 뼈대가 튄다)
            val stageDark = !showCamera && !personLost
            SkeletonStage(
                sample = sample, mirror = useFrontCamera, dark = stageDark, lock = stageLock.takeIf { !preparing },
                // 바닥은 전부 beta — 빨강(ship 전용) 대신 '참고' 채널로(§99, Q1)
                highlight = if (preparing || mode == CoachMode.TRACK || paused || isFloorExercise) emptySet() else violHighlight + formHighlight,
                provisional = if (preparing || paused) emptySet() else if (mode == CoachMode.TRACK) comparison.landmarks else if (isFloorExercise) floorFeedback?.landmarks.orEmpty() else (provisionalHighlight + formProvHighlight) - formHighlight,
                visibilityCut = if (isFloorExercise) 0.35f else 0.5f,
                plankSide = alignment.visibleSide.takeIf { !preparing && alignment.placementReady && mode == CoachMode.COACH && !paused },
                // 화살표는 COACH 만(TRACK 은 앱이 가르치지 않는다 — 원칙 #3). 바닥도 그린다(§101, 참고색 — U7)
                cue = motionCue.takeIf { !preparing && mode == CoachMode.COACH && !paused },
                fullWidth = profile?.stageFullWidth == true, edgeGhost = !isFloorExercise,
                modifier = Modifier.fillMaxSize(),
            )
            if (preparing) PreparationFramingOverlay(sample, Modifier.fillMaxSize())   // 무대 위에 — 검은 배경이 덮지 않게
            if (preparing) Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(
                listOf(Color(0xBB10140E), Color.Transparent))).padding(20.dp)) {
                val goal = workout.resolvedTarget()
                Text("$setLabel · 목표 ${goal.amount}${if (goal is WorkoutTarget.Duration) "초" else "회"}", color = Color.White, fontSize = 13.sp)
                Text(workout.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!preparing && !panelVisible) {
                LiveQuickActions(onOpen = { openPanel() }, onPause = onTogglePause, paused = paused,
                    // 세트 끝 — 지금까지 센 수로 마감. 목표 미달이면 '여기까지 기록'(onPartial) 경로, 시간 목표는 바로 다음으로
                    onFinish = {
                        val goal = workout.resolvedTarget()
                        if (goal is WorkoutTarget.Repetitions) { onRepetitions(repetitions); if (repetitions < goal.amount) onPartial() }
                        else onNext()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
    }

    val modeControl: @Composable () -> Unit = {
        CoachModeControl(mode, preparing) { selected ->
            openPanel()
            mode = selected; refs.modeRef[0] = selected
            modeStore.set(workout.name, selected)
            speech.stop(); comparisonSpeech.clear()
            coachBanner = null; provisionalNote = null
            violHighlight = emptySet(); provisionalHighlight = emptySet()
            formHighlight = emptySet(); formProvHighlight = emptySet()
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
            }, onExit = onExit, modifier = mod, modeControl = modeControl, onShowGuide = onShowGuide,
            guideName = workout.name,
            onTogglePause = onTogglePause,
            cameraError = cameraError ?: stats?.error?.let { "몸을 인식할 수 없어요. 직접 기록으로 계속할 수 있어요." }, onFallback = onFallbackToTimer,
        ) else Column(mod) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(targetState = liveMessage,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "coach-cue") { msg ->
                        Text(msg ?: "", color = c.text, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    if (workout.resolvedTarget() is WorkoutTarget.Repetitions) {
                        // 좌우 짝 단위는 단위와, 한쪽을 마친 동안 '반대쪽 차례' 를 붙인다 — 화면 전용(쪽을 모르므로 말하지 않는다, 원칙 #6)
                        val autoLabel = listOfNotNull("자동 횟수 · 참고",
                            SIDE_PAIR_UNIT_HINT.takeIf { repUnit == RepUnit.SIDE_PAIR },
                            "왼·오 따로 셈".takeIf { sideTallies != null },
                            SIDE_PAIR_NEXT_HINT.takeIf { repUnit == RepUnit.SIDE_PAIR && repHalfPending }).joinToString(" · ")
                        Text(if (refs.repRef[0] == null) "직접 횟수 기록" else if (validation) RepValidation.BANNER else autoLabel, color = c.text2,
                            fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            modeControl()
            WorkoutSessionActions(workout, repetitions, refs.repRef[0] != null, paused,
                onTogglePause, onRepetitions, onPartial, onSkip,
                onShowGuide = onShowGuide,
                onExit = {
                    refs.finalizeRef[0]?.invoke(aihubExercise, workout.name, isFloorExercise)?.let(onSetReport)
                    onExit()
                },
                onExpandCamera = { closePanel() },
                directTools = {
                    SessionTool(if (muted) "음성 꺼짐" else "음성 켜짐", if (muted) "음성 안내 켜기" else "음성 안내 끄기",
                        if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        { muted = !muted }, Modifier.weight(1f))
                    SessionTool("카메라", "카메라 전환", Icons.Rounded.Cameraswitch, {
                        useFrontCamera = !useFrontCamera
                        refs.comparisonRef[0]?.reset(); comparison = ComparisonSnapshot(); comparisonSpeech.clear()
                        synchronized(repRecords) {
                            refs.repRef[0]?.let {
                                it.resetCycle(); refs.repUnitRef[0]?.onCounterCycleReset()   // 끝낸 한쪽은 유지 — 일시정지와 같다
                                refs.repFormRef[0]?.takeIf { f -> f.stepSides || f.legProfile != null }?.discardWindow()
                                repResets += RepResetEvent(System.currentTimeMillis(), "camera_switch", refs.lastCounterFrameAt[0].takeIf { t -> t > 0L })
                            }
                        }
                    }, Modifier.weight(1f))
                    // 무대 토글(§2.1) — 영상을 보고 싶을 때. 세션 동안 유지
                    SessionTool(if (showCamera) "영상" else "뼈대만", if (showCamera) "뼈대만 보기" else "카메라 영상 보기",
                        Icons.Rounded.PhotoCamera, { showCamera = !showCamera }, Modifier.weight(1f))
                    // 숫자 읽기 토글(§7) — 기본은 틱. 바닥 종목은 화면을 못 보므로 기본 켬
                    SessionTool(if (speakNumbers) "숫자 읽기" else "틱 소리", if (speakNumbers) "횟수를 틱 소리로" else "횟수를 숫자로 읽기",
                        Icons.AutoMirrored.Rounded.VolumeUp, { speakNumbers = !speakNumbers; refs.floorLive.speakNumbers = speakNumbers }, Modifier.weight(1f))
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
                    hideCount = validation && refs.repRef[0] != null,
                    // 운동 중에는 제어판이 접혀 있어(몰입) 좌우 짝 표기를 HUD 에도 둔다 — 한쪽을 마친 동안 '반대쪽 차례', 아니면 단위
                    sideCount = sideTallies?.takeIf { refs.repRef[0] != null }?.headline(mode == CoachMode.COACH),
                    countNote = when {
                        // 쪽별 카운트(§63): 한쪽을 다 채웠으면 반대쪽 차례, 좌우 미확인·자세로 뺀 걸음을 밝힌다
                        // 검증 모드는 앱이 센 수·차례를 보이지 않는다(§61) — 단위만
                        refs.repRef[0] != null && sideTallies != null && validation -> "왼·오 따로 셈"
                        refs.repRef[0] != null && sideTallies != null -> sideTallies!!.let { st ->
                            val coachNow = mode == CoachMode.COACH; val t = st.of(coachNow)
                            val unidentified = refs.repRef[0]?.legTracker?.audibleRejected ?: 0   // §101: 관측 붕괴 사이클(blip·edge)은 HUD 에도 들지 않는다
                            listOfNotNull("횟수·자세 검증 중".takeIf { refs.repRef[0]?.legTracker != null }, st.next(coachNow)?.let { "${it.label} 차례" },
                                "좌우 미확인 ${t.unknown}".takeIf { t.unknown > 0 }, "세지 않은 동작 $unidentified".takeIf { unidentified > 0 },
                                "자세로 뺀 걸음 ${t.blocked}".takeIf { coachNow && t.blocked > 0 }).joinToString(" · ").ifEmpty { null }
                        }
                        // 바닥 반복 계열(§99) — 세지 않은 동작 n · 마지막 사유. 검증 모드는 보이지 않는다(§61)
                        refs.repRef[0]?.floorTracker != null -> refs.floorLive.floorNote.takeIf { !validation }
                        refs.repRef[0] != null && repUnit == RepUnit.SIDE_PAIR -> if (repHalfPending) SIDE_PAIR_NEXT_HINT else SIDE_PAIR_UNIT_HINT
                        // COACH: ship 자세 검사 위반 회는 횟수에서 뺀다(§62b) — 큰 숫자가 정확 수, 감지 수는 나란히. 부분 반복(§62c)도 같은 줄에
                        repIncorrect > 0 || (partialExcludedNow && repInvalid > 0) ->
                            "감지 ${repCount + repInvalid}회 중 ${repCounted}회만 셌어요" +
                                (if (partialExcludedNow && repInvalid > 0) " · 부분 ${repInvalid}회" else "") +
                                (if (repIncorrect > 0) " · 자세 ${repIncorrect}회" else "")
                        else -> null
                    },
                    countNoteActive = !validation && (repHalfPending || sideTallies?.next(mode == CoachMode.COACH) != null),
                    elapsedSec = setElapsedSec,
                    // beta 는 '참고' 칩으로만(원칙 #2) — COACH 에서만(TRACK 은 모집단 기준 '틀림' 을 보이지 않는다, §29)
                    referenceNote = provisionalNote?.takeIf { mode == CoachMode.COACH && !validation },
                    modeLabel = if (mode == CoachMode.TRACK) "TRACK" else "COACH",
                    // 호흡(§5): 카운터 위상을 따라가는 표시. 카운터 없는 시간 목표(플랭크)는 "자연스럽게 호흡". 검증 모드·사람 없음·쉼이면 없음
                    breath = when {
                        validation || personLost || paused -> null
                        refs.repRef[0] != null -> com.example.trex_kotlin.posture.Breathing.word(aihubExercise, breathDir)
                        workout.resolvedTarget() is WorkoutTarget.Duration -> com.example.trex_kotlin.posture.Breathing.NATURAL
                        else -> null
                    },
                    // 플랭크 유지 시계(§99) — 큰 숫자 = 카메라가 확인한 시간, 멈춘 동안 회색·상태 줄·작은 경과
                    hold = refs.floorLive.hudNow())
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

/**
 * 바닥 종목의 화면 한 줄(§99) — 안내(신호 결측·누운 기준 없음, 몇 초 뒤 지운다) > 바닥 피드백의 관측·측정 문장. beta 확인 필요·범위 복귀는 '참고' 칩으로 가므로
 * 여기 올리지 않고 중립 측정 문장을 둔다(원칙 #2 — 큰 줄은 교정처럼 읽힌다. 배치 지시로 떨어지면 폰을 옮기게 한다). 규칙은 `FloorReasons.liveMessage`(테스트).
 */
private fun floorLiveMessage(guide: String?, feedback: FloorFeedback?): String = com.example.trex_kotlin.posture.FloorReasons.liveMessage(guide, feedback)

/** 세트 마감에 굳힌 바닥 반복 계열의 상세(§99, 세트 상대시각) — 로그와 리포트가 같은 사본을 쓴다. */
private class FloorSetCopy(val rejected: List<com.example.trex_kotlin.posture.FloorRejection>, val reps: List<com.example.trex_kotlin.posture.FloorRep>,
                           val discarded: List<com.example.trex_kotlin.posture.FloorDiscard>, val abstain: List<Long>, val summary: FloorRepSummary)

/** 틱(§7) — 센 회 ACK · 자세로 뺀 회 NACK · 마지막 3회 BEEP2. TTS 와 겹치지 않는 짧은 소리라 코칭 문장을 끊지 않는다. */
/**
 * 준비 프레임의 카운터 피처(§89 후속 2, 바닥 §99) — 서서 하는 종목은 프레임 피처 그대로, 바닥 종목은 세트와 같은 2D 바닥 피처를 준비 전용
 * 추출기로 만든다(세트 추출기의 접지선을 준비 동작으로 오염시키지 않는다). 사람이 없거나 계산된 것이 없으면 null.
 */
private fun prepFeatures(refs: LiveSessionRefs, s: PoseSample, exercise: String): Map<String, Float>? {
    if (!s.detected) return null
    val f = if (refs.floorRef[0]) refs.prepFloorRef[0].computeForExercise(exercise, s.normalizedXy, s.visibility, s.imageWidth, s.imageHeight)
        else s.features
    return f.takeIf { it.isNotEmpty() }
}

private fun repTick(tone: ToneGenerator?, kind: Int) { runCatching { tone?.startTone(kind, 70) } }

/** 서서 하는 종목의 가로 잘림 감시 결과(§101) → 화면 커버리지(바닥 `FloorCoverage` 와 같은 자리). */
private fun com.example.trex_kotlin.posture.StandingFraming.Report.toCoverage(): CoverageReport =
    if (ok) CoverageReport.OK else CoverageReport(false, listOf(PartStatus(BodyPart.ANKLE, false, if (outRight == true) OutDirection.RIGHT else OutDirection.LEFT)), emptyMap(), message, fix)

/** 렙 카운트 알림 — 음성이 되면 숫자로, 안 되면 짧은 톤으로. 코칭 문구를 끊지 않게 큐에 붙인다. */
private fun speakRep(speech: SpeechCoach, tone: ToneGenerator?, n: Int) {
    if (speech.ready) {
        speech.speak(n.toString(), flush = false, key = "n")   // 최신 숫자만(§101 U4)
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



/** `PostureLiveSessionScreen` 이 분석 스레드와 나눠 쓰는 단일 원소 배열 ref 들 — 세션(화면) 수명. 값 의미는 사용처 주석에. */
private class LiveSessionRefs {
    val normalReferenceRef = arrayOf(NormalPoseReference(emptyList()))
    val floorRef = booleanArrayOf(false)
    val prepFloorRef = arrayOf(FloorFeatureExtractor())   // §99 준비 프레임 전용 바닥 피처 — 세트 추출기의 접지선을 준비 동작으로 오염시키지 않는다
    val validationRef = booleanArrayOf(false)
    val holdRef = arrayOfNulls<HoldTracker>(1)
    val floorFeedbackRef = arrayOfNulls<FloorFeedbackController>(1)
    val alignmentRef = arrayOfNulls<PlankAlignmentTracker>(1)
    val comparisonRef = arrayOfNulls<PostureComparisonTracker>(1)
    val referenceViewRef = arrayOfNulls<String>(1)
    val baselineRef = arrayOfNulls<Map<String, Float>>(1)
    val repRef = arrayOfNulls<RepCounter>(1)
    val repUnitRef = arrayOfNulls<RepUnitAccumulator>(1)
    val sideCounterRef = arrayOfNulls<SideStepCounter>(1)
    val sideCueAtRef = longArrayOf(0L)
    val repFormRef = arrayOfNulls<RepFormEvaluator>(1)
    val rejectedSeenRef = intArrayOf(0)
    val sideSyncPending = booleanArrayOf(false)
    val modeRef = arrayOf(CoachMode.COACH)
    val invalidCuesRef = intArrayOf(0)
    val romShortSpokenRef = booleanArrayOf(false)   // §100 가동 범위 미달 문장을 말했다 — 다음 끝까지 한 회가 "교정됐어요"
    val heardKeyRef = arrayOfNulls<String>(1)       // §101 이 회의 지적 장부 키(rf:<id>·rom) — 교정 문장은 재생을 시작한 지적에만
    val setScopedRef = booleanArrayOf(false)        // §101 이 회의 문장이 세트 범위(쪽 전환)인가 — 세트 끝 꼬리에서 뺀다
    val framingRef = arrayOf(com.example.trex_kotlin.posture.StandingFraming())   // §101 서서 하는 종목의 세트 중 가로 잘림 감시
    val angleNoteAtRef = longArrayOf(0L)
    val detectStartRef = longArrayOf(0L)
    val anchoredRef = booleanArrayOf(false)
    val anchorAtRef = longArrayOf(0L)
    val provisionalAtRef = longArrayOf(0L)
    val lastCounterFrameAt = longArrayOf(0L)   // 0 = 이 세트에서 카운터가 아직 프레임을 보지 않았다
    val floorRulesRef = arrayOfNulls<List<PostureRule>>(1)
    val coverageStreak = intArrayOf(0)
    val lastCoverageSpeakAt = longArrayOf(0L)
    val muteBeforeValidation = arrayOfNulls<Boolean>(1)
    val boundaryUntil = longArrayOf(0L)
    val lastInferAt = longArrayOf(0L)
    val judgeBinRef = longArrayOf(-1L)   // §96 판정 격자의 마지막 칸(now / SESSION_SAMPLE_INTERVAL_MS)
    val floorLive = FloorLiveState()   // §99 바닥 계열의 라이브 상태(종목·플랭크 시계·HUD·기각 이유 간격) — 지역 변수를 늘리지 않는다
    val contactRef = arrayOf(com.example.trex_kotlin.posture.ContactMinimum.elbowKnee())   // §98a 85 ms 접촉 최솟값(판정 격자의 예외)
    val thermalRef = intArrayOf(InferencePolicy.THERMAL_NONE)
    val rotationRef = intArrayOf(android.view.Surface.ROTATION_0)   // 매 컴포지션에 displayRotation 으로 덮어쓴다
    val analysisRef = arrayOfNulls<ImageAnalysis>(1)
    val previewRef = arrayOfNulls<Preview>(1)
    val lensRef = arrayOfNulls<com.example.trex_kotlin.posture.LensInfo>(1)   // 바인딩된 카메라의 렌즈 사양(§91 배치 지문)
    val subjectHeightCm = floatArrayOf(0f)   // 프로필 키(§91) — 0 = 없음
    val frontRef = booleanArrayOf(true)
    val pausedRef = booleanArrayOf(false)
    val coachRef = arrayOfNulls<LiveCoach>(1)
    val finalizeRef = arrayOfNulls<(String, String, Boolean) -> PostureSetReport?>(1)
}

private suspend fun Context.awaitCameraProviderLive(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}
