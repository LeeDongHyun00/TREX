package com.example.trex_kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.posture.CoachMode
import com.example.trex_kotlin.posture.FormLabel
import com.example.trex_kotlin.posture.OnsetKind
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.RuleOutcome
import com.example.trex_kotlin.posture.SetVerdict
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 세션 화면 (리디자인) — 일반 운동은 링 타이머, 완료 화면.
 * 자세교정 세션(실 카메라 + 규칙 엔진)은 PostureLive.kt 의 [PostureLiveSessionScreen].
 */

// ============================================================= 타이머 세션

@Composable
fun TimerSessionScreen(
    workout: Workout,
    index: Int,
    total: Int,
    timeLeft: Int,
    totalSeconds: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val c = Trex.c
    KeepScreenOn()
    Column(Modifier.fillMaxSize().background(c.bg).padding(start = 20.dp, end = 20.dp, top = 50.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("진행중 · ${index + 1}/$total", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(workout.name, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            }
            RoundIcon(Icons.Rounded.Close, onClick = onExit, size = 38.dp, contentDescription = "종료")
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RingGauge(progress = 1f - (timeLeft / totalSeconds.toFloat()), size = 224.dp, stroke = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("남은 시간", color = c.text3, fontSize = 11.sp)
                    Text(timeLeft.asClock(), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, lineHeight = 46.sp, modifier = Modifier.padding(top = 5.dp))
                    Text(workout.reps, color = c.primaryText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(
                Modifier
                    .padding(top = 22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(14.dp))
                Text("${workout.category} · 자세 교정 미사용", color = c.text2, fontSize = 11.5.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(
            Modifier.padding(bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundIcon(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                onClick = onTogglePause,
                size = 52.dp,
                contentDescription = "일시정지",
            )
            Cta("다음 운동", icon = Icons.Rounded.Check, onClick = onNext, height = 56.dp, modifier = Modifier.weight(1f))
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
    reports: Map<String, PostureSetReport> = emptyMap(),
    /** (setId, actualReps, repsSource "edited"|"confirmed"|null, form). */
    onLabel: (setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) -> Unit = { _, _, _, _ -> },
    /** 세션 스코프 스피커 — 라이브 화면의 음소거 상태를 그대로 따른다. */
    speak: (String) -> Unit = {},
    onDone: () -> Unit,
) {
    val c = Trex.c
    val doneCount = plan.count { it.done }
    val kcal = plan.filter { it.done }.sumOf { it.estimatedCalories() }
    // plan 순서로 늘어놓은 리포트 — 헤드라인 선택과 운동별 행이 같은 순서를 쓴다
    val ordered = plan.mapNotNull { reports[it.id] }
    // 제목이 바로 아래에서 지적한 내용을 뒤집어 말하지 않도록: 리포트가 없거나 전부 깨끗/교정일 때만 "정확하게".
    // 유보·참고만 남은 세션도 판정하지 못한 것을 판정한 것처럼 단정하지 않는다.
    val allClean = ordered.all { it.verdict == SetVerdict.CLEAN || it.verdict == SetVerdict.RECOVERED }
    val headline = sessionHeadline(ordered)

    if (ordered.isNotEmpty()) SessionHeadlineVoice(headline, speak)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            // 스크롤이 생기면 내용이 시스템 바 밑으로 들어갈 수 있다 — 다른 화면과 같이 인셋을 먼저 뺀다
            .statusBarsPadding()
            .navigationBarsPadding()
            // 운동 5개 + 펼침이면 화면을 넘는다. 스크롤 컨테이너는 최소 높이를 화면 높이 그대로 넘겨주므로
            // 내용이 짧을 때는 아래 Arrangement.Center 가 예전처럼 중앙 정렬로 동작한다.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(modifier = Modifier.size(82.dp), shape = CircleShape, color = c.primary, contentColor = Color.White, shadowElevation = 8.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(38.dp)) }
        }
        Text("DONE", color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            if (allClean) "오늘도 정확하게 끝냈어룡" else "오늘도 끝까지 해냈어룡",
            color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "조금씩 좋아지고 있어요.\n내일 같은 시간에 만나룡.",
            color = c.text2, fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "$doneCount" to "완료 운동",
                "${(elapsedSeconds / 60).coerceAtLeast(1)}분" to "총 시간",
                "${kcal}kcal" to "소모 칼로리",
            ).forEach { (v, label) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(18.dp))
                        .padding(vertical = 13.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(v, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                    Text(label, color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        if (ordered.isNotEmpty()) {
            PostureSessionBlock(reports = ordered, headline = headline, onLabel = onLabel, modifier = Modifier.padding(top = 20.dp))
        }
        Cta("홈으로", icon = Icons.Rounded.Home, onClick = onDone, modifier = Modifier.padding(top = 26.dp).fillMaxWidth())
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
 * 세션 헤드라인 한 줄 — 우선순위 ISSUE > REFERENCE > RECOVERED > (유보/깨끗). [reports] 는 plan 순서.
 * 판정하지 못한 세트가 섞이면 "오늘 자세 깨끗" 처럼 전체를 단정하지 않는다(정직성 원칙).
 */
private fun sessionHeadline(reports: List<PostureSetReport>): String {
    reports.firstOrNull { it.verdict == SetVerdict.ISSUE && it.callout != null }?.let { r ->
        return "${r.workoutName} ${r.callout!!.bodyPart} — 오늘 가장 신경 쓸 부위예요"   // 그대로 발화되므로 문장으로
    }
    // TRACK 의 베타 후보는 행에 보여 줄 자리가 없다(callout 은 DRIFT/RECOVERED 만) — 헤드라인으로도 올리지 않는다
    reports.firstOrNull { it.verdict == SetVerdict.REFERENCE && it.mode == CoachMode.COACH }?.let { r ->
        return "${r.workoutName} ${r.candidates.first().bodyPart} — 검증 중인 항목이라 참고만 하세요"
    }
    reports.firstOrNull { it.verdict == SetVerdict.RECOVERED && it.callout != null }?.let { r ->
        return "${r.workoutName} ${r.callout!!.bodyPart} — 세트 후반에 교정됐어요"
    }
    return when {
        reports.all { it.verdict == SetVerdict.UNJUDGED } -> "자세를 판정할 만큼 화면에 잡히지 않았어요"
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
                when (report.mode) {
                    CoachMode.COACH -> CoachSetDetail(report)
                    CoachMode.TRACK -> TrackSetDetail(report)
                }
                SelfLabelSlot(report = report, draft = draft, onDraft = onDraft, onLabel = onLabel)
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
            Text("${lead.observation} — 아직 검증 중인 항목이라 참고만 하세요", color = c.text2, fontSize = 12.5.sp, lineHeight = 18.sp)
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
        r.repsValid?.let { valid -> Text("렙 유효 $valid · 무효 ${r.repsPartial ?: 0}", color = c.text3, fontSize = 11.sp) }
        r.highlights.filter { it.ruleId != lead?.ruleId }.forEach { OutcomeLine(it) }
        h?.note?.let { Text("ⓘ $it", color = c.text3, fontSize = 10.5.sp, lineHeight = 15.sp) }
    }
}

/** TRACK 펼침: 템포·렙(파셜), 세트 내 변화, 접힌 측정 기록(판정이 아니라 측정 — §29). */
@Composable
private fun TrackSetDetail(r: PostureSetReport) {
    val c = Trex.c
    var showDemoted by remember(r.setId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val parts = buildList {
            r.repsValid?.let { valid ->
                val partial = r.repsPartial ?: 0
                add("${valid + partial}렙" + if (partial > 0) " · 파셜 $partial" else "")
            }
            r.tempoMs?.let { add("템포 " + String.format(Locale.US, "%.1f초", it / 1000f)) }
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = c.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        // 세트 내 변화는 본인 초반 창 대비라 기록 모드에도 보여 준다
        r.callout?.let { h ->
            Text(h.observation, color = if (h.kind == OnsetKind.DRIFT) c.warn else c.primaryText, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (r.judged == 0) Text("자세 측정 없음 · ${r.frames}프레임", color = c.text3, fontSize = 11.sp)
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
                Text("실제 몇 개 하셨어요?", color = c.text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
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
fun KeepScreenOn(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = context.findTrexActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

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

data class WorkoutFeedback(
    val beep: () -> Unit,
    val speak: (String) -> Unit,
)

@Composable
fun rememberWorkoutFeedback(muted: Boolean): WorkoutFeedback {
    val context = LocalContext.current
    val mutedState = rememberUpdatedState(muted)
    val ttsState = remember { mutableStateOf<TextToSpeech?>(null) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 62) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
            }
        }
        ttsState.value = engine
        onDispose {
            ttsState.value = null
            engine.shutdown()
            toneGenerator.release()
        }
    }

    return remember {
        WorkoutFeedback(
            beep = {
                if (!mutedState.value) {
                    runCatching { toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
                }
            },
            speak = { text ->
                if (!mutedState.value) {
                    ttsState.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trex-${System.nanoTime()}")
                }
            },
        )
    }
}
