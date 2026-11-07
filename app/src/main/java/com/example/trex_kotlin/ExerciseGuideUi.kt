package com.example.trex_kotlin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 기존 행의 이름·스위치·편집 동작을 유지하고 작은 안내 진입점만 추가한다. */
@Composable
internal fun ExerciseGuideButton(name: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    if (ExerciseGuides.forName(name) == null) return
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "$name 운동 방법" },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Trex.c.primaryText, modifier = Modifier.size(18.dp))
        Text("운동 방법", color = Trex.c.primaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/** 목록·운동 선택 화면의 미리보기는 세션 상태에 영향을 주지 않는다. */
@Composable
internal fun ExerciseGuidePreview(name: String, enabled: Boolean = true) {
    var open by rememberSaveable(name) { mutableStateOf(false) }
    ExerciseGuideButton(name, { open = true }, enabled = enabled)
    if (open) ExerciseGuides.forName(name)?.let { guide ->
        ExerciseGuideSheet(guide, onClose = { open = false })
    }
}

/** 안내를 여는 쪽에서 측정·시계를 멈춘다. 닫기는 세트 완료나 자동 재개를 호출하지 않는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseGuideSheet(guide: ExerciseGuide, onClose: () -> Unit,
    confirmLabel: String = "닫기", onConfirm: () -> Unit = onClose, sessionPaused: Boolean = false) {
    val c = Trex.c
    val lifecyclePaused = rememberTrexLifecyclePaused()
    var section by rememberSaveable(guide.name) { mutableIntStateOf(0) }
    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().height(660.dp).padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 20.dp)) {
            Text(guide.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("운동 방법", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(260.dp)
                    .clip(RoundedCornerShape(20.dp)).background(c.sheet), contentAlignment = Alignment.Center) {
                    GuideVisualView(guide.asset, "${guide.name} 동작 예시", playing = !lifecyclePaused, Modifier.fillMaxSize()) { failed ->
                        Text(if (failed) "시범을 불러오지 못했어요." else "시범을 준비하고 있어요.", color = TrexTextSecondary)
                    }
                }
                // 시범 그림의 색 범례(UI 감사 A20, K3) — 빨강 = 주 근육, 주황 = 보조·안정. 세션의 빨강(회 제외)·주황(말함)과는 다른 어휘라 그림 바로 아래 한 줄로 밝힌다
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    GuideLegend(Color(0xFFD94A3D), "주 근육"); GuideLegend(Color(0xFFE39A3C), "보조·안정")
                    Text("그림 속 색 · 자극 순위가 아니에요", color = c.text3, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                SegmentedTabs(listOf("운동 가이드", "주의사항"), section,
                    onSelect = { section = it })
                Spacer(Modifier.height(20.dp))
                when (section) {
                    0 -> {
                        GuideSection("시작 자세", guide.setup)
                        GuideSection("운동 동작", guide.steps)
                        GuideSection("호흡법", listOf(guide.breathing), numbered = false)
                    }
                    1 -> GuideSection("주의사항", guide.cautions)
                }
                Text("시범의 각도와 권장 촬영 방향은 다를 수 있어요.", color = c.text2,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            }
            if (sessionPaused) Text("운동은 일시정지 중이에요. 닫은 뒤 직접 재개해 주세요.",
                color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            Cta(confirmLabel, onConfirm, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun GuideLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, color = Trex.c.text2, fontSize = 11.sp)
    }
}

@Composable
private fun GuideSection(title: String, paragraphs: List<String>, numbered: Boolean = true) {
    Text(title, color = Trex.c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp))
    paragraphs.forEachIndexed { index, paragraph ->
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (numbered) Text("${index + 1}.", color = Trex.c.text2, fontSize = 14.sp)
            Text(paragraph, color = Trex.c.text, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(20.dp))
}

