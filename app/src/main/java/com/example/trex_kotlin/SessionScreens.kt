package com.example.trex_kotlin

import com.example.trex_kotlin.posture.FloorTemporal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.FormLabel
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.RepUnit
import com.example.trex_kotlin.posture.RuleOutcome
import com.example.trex_kotlin.posture.SIDE_PAIR_UNIT_HINT
import com.example.trex_kotlin.posture.SetVerdict
import kotlinx.coroutines.delay

/**
 * 세션 화면 (리디자인) — 일반 운동은 링 타이머, 완료 화면.
 * 자세교정 세션(실 카메라 + 규칙 엔진)은 PostureLive.kt 의 [PostureLiveSessionScreen].
 */

// ============================================================= 타이머 세션

@Composable
fun TimerSessionScreen(
    workout: Workout, index: Int, total: Int, timeLeft: Int, totalSeconds: Int,
    paused: Boolean, onTogglePause: () -> Unit, onNext: () -> Unit, onExit: () -> Unit,
    setLabel: String = "1 / 1 세트", onSkip: () -> Unit = onNext,
    repetitions: Int = 0, onRepetitions: (Int) -> Unit = {}, onPartial: () -> Unit = onSkip,
    onShowGuide: (() -> Unit)? = null,
) {
    val c = Trex.c
    KeepScreenOn()
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(22.dp)) {
        Text(workout.name, color = c.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("${index + 1}/$total 운동 · $setLabel", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            WorkoutGoalDisplay(workout, repetitions, timeLeft, totalSeconds)
            Text(if (paused) "일시정지" else if (workout.resolvedTarget() is WorkoutTarget.Repetitions) "직접 횟수 기록" else "시간 측정",
                color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 28.dp))
        }
        // 제어판 디자인(스펙 §115) — 흰 둥근 판 위 원형 버튼 줄
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = c.surface, shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                WorkoutSessionActions(workout, repetitions, false, paused, onTogglePause, onRepetitions, onPartial, onSkip, onExit,
                    onShowGuide = onShowGuide)
            }
        }
    }
}

/** 준비는 직접 시작한다. 휴식에만 원형 타이머와 자동 전환을 제공한다. */
@Composable
fun SessionTransitionScreen(step: SessionStep, timeLeft: Int, paused: Boolean,
    onTogglePause: () -> Unit, onNext: () -> Unit, onExit: () -> Unit, onShowGuide: (() -> Unit)? = null,
    /** 세트 입력 카드(§131 P3) — 있으면 큰 링 대신 작은 링 + 세트 입력을 보인다. */
    setInput: (@Composable () -> Unit)? = null, onExtendRest: (() -> Unit)? = null) {
    KeepScreenOn()
    val c = Trex.c
    val preparing = step.phase == SessionPhase.PREPARE
    if (preparing) {
        TimedPreparationScreen(step, paused, onTogglePause, onNext, onExit, onShowGuide)
        return
    }
    val progress = (1f - timeLeft.toFloat() / step.seconds.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp).padding(top = 16.dp, bottom = 20.dp)) {
        if (setInput != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("세트 사이 휴식 · ${step.setLabel}", color = c.primaryText, fontSize = 13.sp)
                    Text(step.workout.name, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
                // 폭을 주지 않으면 GhostButton 의 fillMaxWidth 가 제목 칸을 한 글자 폭으로 밀어낸다(11-07 실기기)
                if (onExtendRest != null) GhostButton("+30초", onExtendRest, Modifier.width(96.dp), height = 44.dp)
            }
            // 남은 휴식이 1차(UI 감사 A14, K1) — 오른쪽 위 64 dp 링 대신 큰 숫자. 세트 입력은 그 아래, 방금 세트 한 줄만 펼친다
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(timeLeft.asClock(), color = if (paused) c.warn else c.text, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                Text(if (paused) "일시정지" else "남은 휴식", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 7.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                setInput()
                if (onShowGuide != null) ExerciseGuideButton(step.workout.name, onShowGuide)
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Text("세트 사이 휴식", color = c.primaryText, fontSize = 14.sp)
            Text(step.workout.name, color = c.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("${step.setLabel} · ${step.workout.repsSpec().targetLabel}", color = c.text2, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                    RingGauge(progress = progress, size = 224.dp, stroke = 9.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(timeLeft.asClock(), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (paused) "일시정지" else "남은 휴식", color = c.text2, fontSize = 13.sp)
                        }
                    }
            }
            if (onShowGuide != null) ExerciseGuideButton(step.workout.name, onShowGuide)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(if (paused) "재개" else "일시정지",
                onClick = onTogglePause, modifier = Modifier.width(100.dp))
            RestProgressCta(if (setInput != null) "다음 세트" else "휴식 끝내기", progress, onNext, Modifier.weight(1f))
        }
    }
}

/**
 * 휴식 진행이 차오르는 주 행동 버튼(UI 감사 A14, K4 값 변화 알림) — 왼쪽부터 진한 primary 가 남은 비율만큼 채워진다. 1초마다 오는 값을 1초 선형 보간으로 이어
 * 눈에 뚝뚝 끊기지 않는다. 글자는 늘 흰색이라 채움이 지나가도 읽힌다.
 */
@Composable
private fun RestProgressCta(text: String, progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Trex.c
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1000, easing = LinearEasing), label = "rest-fill")
    // 바탕은 불투명(배경과 66 % 섞은 primary) — 반투명 표면은 그림자가 비쳐 안쪽만 밝은 띠로 보였다(11-07 실기기)
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp), color = lerp(c.bg, c.primary, .66f), contentColor = Color.White, shadowElevation = 2.dp) {
        Box {
            Box(Modifier.matchParentSize().drawBehind { drawRect(c.primary, size = Size(size.width * shown, size.height)) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

// ============================================================= 완료

/**
 * 완료 화면. [reports] 는 이번 세션의 자세 세트 리포트(workoutId → report) — 비어 있으면 기존 3타일 화면 그대로다.
 * [onLabel] 은 사용자가 세트 자가 라벨(실제 렙 수 · 폼 자평)을 저장할 때 (setId, actualReps, form) 으로 호출된다.
 */
@Composable
fun SessionCompleteScreen(
    plan: List<Workout>,
    elapsedSeconds: Int,
    elapsedByWorkout: Map<String, Int> = emptyMap(),
    /** kcal 식의 체중(§134b) — 프로필 체중(체중 기록이 갱신). */
    bodyKg: Double = DEFAULT_BODY_KG,
    reports: Map<String, PostureSetReport> = emptyMap(),
    /** (setId, actualReps, repsSource "edited"|"confirmed"|null, form). */
    onLabel: (setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) -> Unit = { _, _, _, _ -> },
    /** 세션 스코프 스피커 — 라이브 화면의 음소거 상태를 그대로 따른다. */
    speak: (String) -> Unit = {},
    fatigueContent: @Composable () -> Unit = {},
    /** 커뮤니티에 오운완 카드 올리기(커뮤니티를 쓸 수 있을 때만, 없으면 버튼이 없다). */
    onShare: (() -> Unit)? = null,
    onDone: () -> Unit,
    /** 보상 띠(§131 P5) — 기록한 사실에만 붙는 XP·PR·연속·새 배지. 자세 판정·카메라 횟수와 무관. */
    reward: SessionReward? = null,
    onOpenAchievements: (() -> Unit)? = null,
    /** 세트 기록 확인·수정(마지막 세트 입력 자리, PR 65 리뷰). */
    setsContent: @Composable () -> Unit = {},
) {
    val c = Trex.c
    val doneCount = plan.count { it.done }
    // 세션 총 경과(휴식 포함)를 종목 가동 비율로 나눠 MET 적용(§134b) — 기록(`createWorkoutHistoryDay`)·오운완 카드와 같은 식
    val kcal = sessionKcalByWorkout(plan, elapsedSeconds, elapsedByWorkout, bodyKg).values.sum()
    // plan 순서로 늘어놓은 리포트 — 헤드라인 선택과 운동별 행이 같은 순서를 쓴다
    val ordered = plan.mapNotNull { reports[it.id] }
    val headline = sessionHeadline(ordered)
    val completionScroll = rememberScrollState()

    if (ordered.isNotEmpty()) SessionHeadlineVoice(headline, speak)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 실제 내용이 가용 높이를 넘는 경우만 스크롤한다. 기기 종류/방향으로 추측하지 않는다.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(
            completionScroll, enabled = completionScroll.maxValue in 1 until Int.MAX_VALUE,
            overscrollEffect = null,
        ).padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = c.primary, contentColor = Color.White) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(28.dp)) }
        }
        Text(
            if (plan.isEmpty()) "운동을 마쳤어요" else "오늘 운동을 기록했어요",
            color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp),
        )
        val partial = plan.count { !it.done }
        if (partial > 0) Text("부분 수행 ${partial}세트도 함께 저장했어요.", color = c.text2,
            fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        // 1. 기록 수치가 먼저(UI 감사 A21, K9 보상은 보조) — 숫자가 차례로 올라온다(400 ms, 한 번에 하나: K4)
        val volume = reward?.takeIf { it.volumeKg > 0 }?.volumeKg?.toInt()
        val shownSets = countUp(doneCount, 0)
        val shownSeconds = countUp(elapsedSeconds, 120)
        val shownVolume = countUp(volume ?: 0, 240)
        val shownKcal = countUp(kcal, if (volume != null) 360 else 240)
        Row(Modifier.padding(top = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(
                "$shownSets" to "완료 세트",
                shownSeconds.asClock() to "총 시간",
                volume?.let { "${shownVolume}kg" to "볼륨" },
                "${shownKcal}kcal" to "예상 소모",
            ).forEach { (v, label) ->
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 13.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(v, color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                    Text(label, color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        // 2. 보상은 그 아래 한 칸 — XP 바 + 배지 글리프, 글은 한 줄
        if (reward != null && (reward.xp > 0 || reward.personalRecord != null || reward.newBadges.isNotEmpty())) {
            Surface(shape = RoundedCornerShape(18.dp), color = c.primaryWash, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    reward.personalRecord?.let { (name, set) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.EmojiEvents, null, tint = c.primaryText, modifier = Modifier.size(18.dp))
                            // 맨몸 종목의 PR 은 추가 중량 PR — '+' 로 가른다(근거 감사 F5)
                            val plus = if (exerciseLoadKind(name).kgLabel() == "+kg") "+" else ""
                            Text("개인 기록 · $name $plus${set.kg?.let(::trimKg)} kg × ${set.reps}" + (reward.previousBestKg?.let { " (지난 $plus${trimKg(it)})" } ?: ""), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (reward.xp > 0) {
                        Text("+${reward.xp} XP · 연속 ${reward.streak}일", color = c.text, fontSize = 13.sp)
                        XpLevelBar(reward)
                    }
                    if (reward.newBadges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reward.newBadges.forEach { b ->
                            Row(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primary).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.EmojiEvents, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Text(b.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        setsContent()
        fatigueContent()
        if (ordered.isNotEmpty()) {
            PostureSessionBlock(reports = ordered, headline = headline, onLabel = onLabel, modifier = Modifier.padding(top = 20.dp))
        }
        }
        // 면책은 ⓘ 뒤로(K2) — 눌러야 보이고, 접근성 설명에는 남긴다
        if (reward != null) {
            var note by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { note = !note }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Info, "XP 안내", tint = c.text3, modifier = Modifier.size(16.dp)) }
                if (note) Text("XP·배지는 운동을 마치고 기록한 사실에만 붙어요. 자세 판정·카메라 횟수와는 무관해요. 예상 소모 kcal 은 종목별 MET(2024 Compendium) × 체중 ${trimKg(bodyKg)} kg × 세션 시간(휴식 포함)으로 어림한 값이에요. 중량·강도·심박은 반영하지 않아요.", color = c.text3, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onShare != null) GhostButton("오운완 올리기", onShare, Modifier.weight(1f), icon = Icons.Rounded.Forum)
            if (onOpenAchievements != null) GhostButton("업적 보기", onOpenAchievements, Modifier.weight(1f))
        }
        Cta("홈으로", icon = Icons.Rounded.Home, onClick = onDone, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
    }
}

/** 완료 화면 수치 카운트업 — [delayMs] 뒤 0 에서 [target] 까지 400 ms(값 변화 알림, 순차라 동시 애니가 아니다). */
@Composable
private fun countUp(target: Int, delayMs: Int): Int {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { delay(delayMs.toLong()); a.animateTo(target.toFloat(), tween(400, easing = FastOutSlowInEasing)) }
    return a.value.toInt()
}

/**
 * 경험치 바(사용자 요청 2026-11-07 "글 대신 경험치 바가 차는 애니메이션") — 지급 전 위치에서 지급 뒤 위치까지 찬다.
 * 레벨이 오르면 바가 끝까지 찬 뒤 0 부터 다시 차고 라벨이 "Lv n → Lv n+1" 로 바뀐다. 숫자도 바와 함께 오른다. 같은 세션 재저장(xp 0)에는 그리지 않는다.
 */
@Composable
private fun XpLevelBar(reward: SessionReward) {
    val c = Trex.c
    val per = Rewards.XP_PER_LEVEL.toFloat()
    val fill = remember(reward) { Animatable((reward.xpInLevelBefore.coerceAtLeast(0)) / per) }
    var level by remember(reward) { mutableIntStateOf(if (reward.leveledUp) reward.level - 1 else reward.level) }
    LaunchedEffect(reward) {
        delay(350)
        if (reward.leveledUp) {
            fill.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
            level = reward.level
            fill.snapTo(0f)
        }
        fill.animateTo(reward.xpInLevelAfter / per, tween(if (reward.leveledUp) 500 else 800, easing = FastOutSlowInEasing))
    }
    val shown = (fill.value * per).toInt()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (reward.leveledUp && level == reward.level) "Lv ${reward.level - 1} → Lv ${reward.level}" else "Lv $level", color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$shown / ${Rewards.XP_PER_LEVEL} XP", color = c.text2, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(10.dp).clip(RoundedCornerShape(999.dp)).background(c.track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value.coerceIn(0f, 1f)).clip(RoundedCornerShape(999.dp)).background(c.primary))
        }
    }
}

// ----------------------------------------------------------- 완료 화면 · 자세 블록

/**
 * 헤드라인에 쓸 지적. COACH 는 리포트 headline 그대로, TRACK 은 세트 내 변화(점점/교정 — 본인 초반 창 대비)만 —
 * 모집단(AIHub) 기준 판정을 숙련자에게 지적으로 내밀지 않는다(spec §29).
 */
private val PostureSetReport.callout: RuleOutcome?
    get() = headline?.takeIf { mode == CoachMode.COACH || it.kind == OnsetKind.DRIFT || it.kind == OnsetKind.RECOVERED }

/**
 * 세션 헤드라인 한 줄 — 우선순위 ISSUE > RECOVERED > (기록/유보/깨끗). [reports] 는 plan 순서.
 * 판정하지 못한 세트가 섞이면 "오늘 자세 깨끗" 처럼 전체를 단정하지 않는다(정직성 원칙).
 */
internal fun sessionHeadline(reports: List<PostureSetReport>): String {
    reports.firstOrNull { it.verdict == SetVerdict.ISSUE && it.callout != null }?.let { r ->
        return "${r.workoutName} ${r.callout!!.bodyPart} — 오늘 가장 신경 쓸 부위예요"   // 그대로 발화되므로 문장으로
    }
    reports.firstOrNull { it.verdict == SetVerdict.RECOVERED && it.callout != null }?.let { r ->
        return "${r.workoutName} ${r.callout!!.bodyPart} — 세트 후반에 교정됐어요"
    }
    return when {
        reports.any { it.verdict == SetVerdict.REFERENCE } -> "운동별 자세 기록을 저장했어요"
        reports.any { it.exercise in FloorTemporal.exercises } -> "바닥 운동의 참고 측정을 기록했어요. 자세 확정 판정은 제공하지 않아요"
        reports.all { it.verdict == SetVerdict.UNJUDGED } -> if (reports.any { it.measurements.isNotEmpty() }) "초반 대비 움직임을 기록했어요" else "자세를 판정할 만큼 화면에 잡히지 않았어요"
        reports.all { it.mode == CoachMode.TRACK && it.verdict != SetVerdict.UNJUDGED } -> "세트 안에서 흐트러진 부위 없이 기록됐어요"
        reports.all { it.mode == CoachMode.COACH && it.verdict == SetVerdict.CLEAN } -> "오늘 자세 깨끗했어요"
        else -> "판정한 세트에서는 지적할 부위가 없었어요"
    }
}

/**
 * 세션 헤드라인을 첫 컴포지션에서 한 번 읽어 준다 — 세션 스코프 스피커의 큐 뒤에 붙으므로(flush 아님)
 * 마지막 세트의 요약 문장이 끝난 다음에 나오고, 라이브 화면의 음소거도 그대로 따른다.
 */
@Composable
private fun SessionHeadlineVoice(text: String, speak: (String) -> Unit) {
    LaunchedEffect(Unit) {
        delay(300)
        speak(text)
    }
}

/** 세트 자가 라벨 입력 상태 — setId 별로 완료 화면 안에서만 산다. [reps] 는 스테퍼가 없는 종목(렙 카운터 미적용)이면 null. */
private data class SetLabelDraft(
    val reps: Int?,
    /** 스테퍼로 고쳤다 — 정답(rep_truth.csv) source=edited. */
    val repsTouched: Boolean,
    /** 앱 카운트를 보고 "맞아요" 로 확인했다 — source=confirmed. 확인 없이 폼만 고른 저장은 렙을 정답으로 넣지 않는다(순환 참조 방지). */
    val repsConfirmed: Boolean,
    val form: FormLabel?,
    val saved: Boolean,
) {
    val canSave: Boolean get() = !saved && (repsTouched || repsConfirmed || form != null)

    /** 정답으로 넘길 렙 수와 출처. 만지지도 확인하지도 않은 스테퍼 값은 넘기지 않는다. */
    val repsForLabel: Int? get() = if (repsTouched || repsConfirmed) reps else null
    val repsSource: String? get() = when { repsTouched -> "edited"; repsConfirmed -> "confirmed"; else -> null }

    companion object {
        fun initial(r: PostureSetReport) =
            SetLabelDraft(reps = r.repsValid?.let { it + (r.repsPartial ?: 0) }, repsTouched = false, repsConfirmed = false, form = null, saved = false)
    }
}

@Composable
private fun PostureSessionBlock(
    reports: List<PostureSetReport>,
    headline: String,
    onLabel: (setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Trex.c
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val drafts = remember { mutableStateMapOf<String, SetLabelDraft>() }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Kicker("자세")
        Text(headline, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        DCard(Modifier.padding(top = 12.dp), radius = 20.dp) {
            Column {
                reports.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    PostureSetRow(
                        report = r,
                        expanded = expanded[r.setId] == true,
                        onToggle = { expanded[r.setId] = expanded[r.setId] != true },
                        draft = drafts[r.setId] ?: SetLabelDraft.initial(r),
                        onDraft = { drafts[r.setId] = it },
                        onLabel = onLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun PostureSetRow(
    report: PostureSetReport,
    expanded: Boolean,
    onToggle: () -> Unit,
    draft: SetLabelDraft,
    onDraft: (SetLabelDraft) -> Unit,
    onLabel: (setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) -> Unit,
) {
    val c = Trex.c
    Column(Modifier.fillMaxWidth()) {
        // 탭 영역은 헤더 행만 — 펼친 안쪽(스테퍼·칩)을 만지다 행이 접히지 않게
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        report.workoutName, color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    VerdictChip(report)
                }
                Text(report.summaryLine, color = c.text2, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(
                Icons.Rounded.ExpandMore, contentDescription = if (expanded) "접기" else "펼치기", tint = c.text3,
                modifier = Modifier.padding(start = 8.dp).size(18.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                report.measurements.forEach { Text(it, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                // 이 종목에서 못 보는 것(spec §99, 원칙 #5) — 바닥 계열 세 종목
                report.scopeLine?.let { Text("범위 · $it", color = c.text3, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                when (report.mode) {
                    CoachMode.COACH -> CoachSetDetail(report)
                    CoachMode.TRACK -> TrackSetDetail(report)
                }
                // 자가 라벨(실제 횟수·자세 자평)은 실검증 도구 — 배포 빌드에는 없다(스펙 §116)
                if (PLATFORM_DEV_TOOLS) SelfLabelSlot(report = report, draft = draft, onDraft = onDraft, onLabel = onLabel)
            }
        }
    }
}

/** 행 오른쪽 칩. TRACK 은 판정 대신 "기록" — 색 구분은 랩 화면과 같이 처음부터(습관)=err, 점점(피로)=warn. */
@Composable
private fun VerdictChip(report: PostureSetReport) {
    val c = Trex.c
    val (text, fg, bg) = if (report.mode == CoachMode.TRACK) {
        Triple("기록", c.text2, c.surface2)
    } else {
        when (report.verdict) {
            SetVerdict.CLEAN -> Triple("깨끗", c.primaryText, c.primaryWash)
            SetVerdict.RECOVERED -> Triple("교정됨", c.primaryText, c.primaryWash)
            SetVerdict.ISSUE -> {
                val h = report.headline
                if (h != null && h.kind == OnsetKind.DRIFT) Triple(h.label, c.warn, c.warnWash) else Triple(h?.label ?: "위반", c.err, c.errWash)
            }
            SetVerdict.REFERENCE -> Triple("참고", c.text2, c.surface2)
            SetVerdict.UNJUDGED -> Triple("판정 없음", c.text3, c.surface2)
        }
    }
    Text(
        text, color = fg, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallTag(text: String) {
    val c = Trex.c
    Text(
        text, color = c.text3, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line, RoundedCornerShape(999.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 후보 한 줄: 규칙 조건 + 라벨 (+베타 태그). 헤드라인 외 항목과 TRACK 의 측정 기록이 같은 모양을 쓴다. */
@Composable
private fun OutcomeLine(o: RuleOutcome) {
    val c = Trex.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${o.bodyPart} · ${o.condition}", color = c.text2, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(o.label, color = c.text3, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (o.beta) {
            Spacer(Modifier.width(5.dp))
            SmallTag("베타")
        }
    }
}

/** COACH 펼침: 관찰·교정, 분모를 드러낸 점수줄(judged==0 이면 점수 없음), 나머지 후보, 측정 주석, 렙. */
@Composable
private fun CoachSetDetail(r: PostureSetReport) {
    val c = Trex.c
    val h = r.headline
    // REFERENCE 는 헤드라인이 없어 첫 베타 후보를 대표로 보여 준다 — 아래 후보 목록에서 그 항목은 뺀다(중복 방지)
    val lead = h ?: r.candidates.firstOrNull()?.takeIf { r.verdict == SetVerdict.REFERENCE }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (h != null) {
            Text(h.observation, color = c.text, fontSize = 12.5.sp, lineHeight = 18.sp)
            if (h.fix.isNotBlank()) {
                Text("다음엔: ${h.fix}", color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            }
        } else if (lead != null) {
            // 베타 규칙만 걸린 세트 — 관찰은 보이되 검증 중임을 붙인다(§28 오탐이 전부 베타/미보정)
            Text("${lead.observation} · 검증 중", color = c.text2, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        // 분모를 드러낸다 — 유보를 정상으로 세지 않고, 베타는 점수 밖("참고")이다. 판정이 없으면 점수줄 자체를 두지 않는다.
        Text(
            when {
                r.judged == 0 -> "판정 없음 · ${r.frames}프레임"
                r.betaOnly -> "검증 중인 항목만 ${r.betaJudged}건 · 점수 없음"
                else -> "정상 ${r.shipOk} / 판정 ${r.shipJudged} · 보류 ${r.abstained}" + if (r.betaJudged > 0) " · 참고 ${r.betaJudged}건" else ""
            },
            color = c.text3, fontSize = 11.sp,
        )
        // 렙 줄의 말은 ROM 판정 단계가 정한다(spec §58) — 검증 기준만 "유효·무효", 미검증은 '참고 · 범위 미달', 기준 없음은 '범위 미판정'
        r.repDetailLine?.let { Text(it, color = c.text3, fontSize = 11.sp) }
        r.highlights.filter { it.ruleId != lead?.ruleId }.forEach { OutcomeLine(it) }
        h?.note?.let { Text("ⓘ $it", color = c.text3, fontSize = 10.5.sp, lineHeight = 15.sp) }
    }
}

/** TRACK 펼침: 템포·렙(ROM 은 판정 단계가 허락하는 말로만 — 파셜은 검증 기준만, spec §58), 세트 내 변화, 접힌 측정 기록(판정이 아니라 측정 — §29). */
@Composable
private fun TrackSetDetail(r: PostureSetReport) {
    val c = Trex.c
    var showDemoted by remember(r.setId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val parts = buildList {
            r.repDetailLine?.let { add(it) }
            r.tempoMs?.let { add("템포 " + formatOneDecimal(it / 1000f) + "초") }
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = c.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        // 세트 내 변화는 본인 초반 창 대비라 기록 모드에도 보여 준다
        r.callout?.let { h ->
            Text(h.observation, color = if (h.kind == OnsetKind.DRIFT) c.warn else c.primaryText, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (r.judged == 0) Text("자세 규칙 판정 없음 · ${r.frames}프레임", color = c.text3, fontSize = 11.sp)
        if (r.demoted.isNotEmpty()) {
            Text(
                "측정 기록 ${r.demoted.size}건 " + if (showDemoted) "▴" else "▾",
                color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showDemoted = !showDemoted }.padding(vertical = 2.dp),
            )
            if (showDemoted) {
                Text("판정이 아니라 측정값이에요 — 본인 기준으로 보세요", color = c.text3, fontSize = 10.5.sp, lineHeight = 15.sp)
                r.demoted.forEach { OutcomeLine(it) }
            }
        }
    }
}

/**
 * 자가 라벨 슬롯 — 실제 렙 수(렙 카운터 종목만) + 폼 자평 칩 + 저장. 저장 뒤엔 "기록됐어요 ✓" 로 잠근다.
 * 렙은 사용자가 스테퍼로 고쳤거나(edited) "이 숫자 맞아요" 로 확인했을 때만(confirmed) 정답으로 넘긴다 — 폼만 고른 저장은
 * 렙 없이 jsonl 에만 남는다. 앱 카운트가 확인 없이 rep_truth 로 흘러가면 재생 검증이 자기 답을 채점하게 된다.
 */
@Composable
private fun SelfLabelSlot(
    report: PostureSetReport,
    draft: SetLabelDraft,
    onDraft: (SetLabelDraft) -> Unit,
    onLabel: (setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) -> Unit,
) {
    val c = Trex.c
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("잘못 짚었다면 알려주세요 — 다음 판정이 좋아져요", color = c.text3, fontSize = 11.sp, lineHeight = 16.sp)
        val reps = draft.reps
        if (reps != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 좌우 짝 단위(런지류, spec §59)의 스테퍼는 화면 수와 같은 단위(쌍)로 시작한다 — 단위를 밝히지 않으면 걸음을 세는 사용자가
                // 두 배를 적고(10 + 10 걸음 → "20"), 연구 도구는 그 라벨을 쌍으로 읽는다. 화면 전용(음성 없음).
                val sidePair = report.repUnit == RepUnit.SIDE_PAIR || report.repUnit == RepUnit.SIDE_EACH
                Column(Modifier.weight(1f)) {
                    Text(if (sidePair) "실제 몇 회 하셨어요?" else "실제 몇 개 하셨어요?", color = c.text2, fontSize = 12.sp)
                    if (sidePair) Text(SIDE_PAIR_UNIT_HINT, color = c.text3, fontSize = 11.sp)
                }
                StepperControl(
                    valueLabel = "$reps",
                    onDec = { if (!draft.saved) onDraft(draft.copy(reps = (reps - 1).coerceAtLeast(0), repsTouched = true, repsConfirmed = false)) },
                    onInc = { if (!draft.saved) onDraft(draft.copy(reps = (reps + 1).coerceAtMost(99), repsTouched = true, repsConfirmed = false)) },
                )
            }
            // 앱 카운트가 맞으면 한 탭으로 확인 — 확인 없는 스테퍼 값은 정답으로 넣지 않는다(카운터가 자기 답을 채점하는 순환 방지)
            if (!draft.repsTouched) {
                val ok = draft.repsConfirmed
                Surface(
                    onClick = { if (!draft.saved) onDraft(draft.copy(repsConfirmed = !ok)) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (ok) c.primaryWash else c.surface,
                    contentColor = if (ok) c.primaryText else c.text2,
                    border = BorderStroke(1.dp, if (ok) c.primarySoftLine else c.line),
                ) {
                    Text(
                        if (ok) "✓ 이 숫자 맞아요" else "이 숫자 맞아요",
                        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FormLabel.values().forEach { f ->
                val sel = draft.form == f
                Surface(
                    onClick = { if (!draft.saved) onDraft(draft.copy(form = if (sel) null else f)) },
                    modifier = Modifier.weight(1f),   // 큰 글꼴·좁은 화면에서 마지막 칩이 잘리지 않게 폭을 나눈다
                    shape = RoundedCornerShape(999.dp),
                    color = if (sel) c.primary else c.surface,
                    contentColor = if (sel) Color.White else c.text2,
                    border = BorderStroke(1.dp, if (sel) Color.Transparent else c.line),
                ) {
                    Box(Modifier.height(30.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(f.displayName, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // GhostButton 에 enabled 가 없어 탭 무시 + 흐리게로 비활성을 표현한다
        GhostButton(
            text = if (draft.saved) "기록됐어요 ✓" else "저장",
            onClick = {
                if (draft.canSave) {
                    onLabel(report.setId, draft.repsForLabel, draft.repsSource, draft.form)
                    onDraft(draft.copy(saved = true))
                }
            },
            modifier = Modifier.fillMaxWidth().alpha(if (draft.saved || draft.canSave) 1f else 0.5f),
            height = 40.dp,
            tone = if (draft.saved) c.primaryText else null,
        )
    }
}

// ============================================================= 공용 유틸 (기존 유지)

@Composable
fun rememberTrexLifecyclePaused(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> paused = true
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> paused = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return paused
}
