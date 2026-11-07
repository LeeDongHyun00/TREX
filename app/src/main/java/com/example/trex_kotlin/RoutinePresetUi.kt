package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.routines.RoutinePreset
import com.example.trex_kotlin.routines.presetParts

/*
 * 루틴 추천 화면의 그림 중심 조각(§132, 사용자 요청 2026-11-07 "Liftoff 처럼 글보다 그림으로 한눈에, 목록을 쉽게 고르게").
 * 참조한 넷: Liftoff(종목마다 그림·근육 그림, 등급 배지), Nike Training Club(부위·장비·수준으로 거른 그림 카드에 시간·강도 라벨),
 * Hevy(장비별 루틴 묶음 타일이 첫 번째 선택, 루틴마다 '저장' 한 번), NN/g 카드 대 목록(둘러보기는 카드, 비교·정렬은 목록).
 * 그래서 루틴은 그림 카드, 루틴 안의 종목은 비교용 목록이다.
 */

/** 장비 타일(Hevy 의 묶음 타일) — 글자 대신 그림 한 개씩, 고른 타일만 primary 로 채운다. [selected] null = 전체. */
@Composable
internal fun EquipmentTiles(selected: String?, onSelect: (String?) -> Unit) {
    val c = Trex.c
    val tiles = listOf<Triple<String?, String, ImageVector>>(
        Triple(null, "전체", Icons.Rounded.Apps), Triple("맨몸", "맨몸", Icons.Rounded.Accessibility), Triple("밴드", "밴드", Icons.Rounded.AllInclusive),
        Triple("덤벨", "덤벨", Icons.Rounded.FitnessCenter), Triple("헬스장", "헬스장", Icons.Rounded.Store),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (key, label, icon) ->
            val on = key == selected
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(onClick = { onSelect(key) }, shape = RoundedCornerShape(16.dp), color = if (on) c.primary else c.surface2,
                    border = BorderStroke(1.dp, if (on) Color.Transparent else c.line), shadowElevation = if (on) 3.dp else 0.dp,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (on) Color.White else c.text2, modifier = Modifier.size(24.dp)) }
                }
                Text(label, color = if (on) c.primaryText else c.text2, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * 부위 칩(NTC 의 부위 둘러보기) — 홈 카드와 같은 그림을 둥글게 잘라 쓴다. 고른 칩에 primary 테두리. [selected] null = 전체.
 * [parts] 는 (이름, `assets/routine/` 그림 키) — 루틴은 [presetParts], 카탈로그는 유산소를 더한 목록(UI 감사 A8).
 */
@Composable
internal fun PartChips(selected: String?, onSelect: (String?) -> Unit, parts: List<Pair<String, String>> = presetParts) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(onClick = { onSelect(null) }, shape = CircleShape, color = if (selected == null) c.primary else c.surface2,
                border = BorderStroke(1.dp, if (selected == null) Color.Transparent else c.line), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("전체", color = if (selected == null) Color.White else c.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
            Text("부위", color = c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        parts.forEach { (key, image) ->
            val on = key == selected
            // 글자까지 눌리게 — 실기기에서 라벨을 누르고 안 바뀌던 것(11-07)
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(if (on) null else key) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = c.surface2,
                    border = BorderStroke(if (on) 3.dp else 1.dp, if (on) c.primary else c.line), modifier = Modifier.size(48.dp)) {
                    Box(Modifier.fillMaxSize().padding(if (on) 3.dp else 1.dp).clip(CircleShape)) { AssetArtwork("routine/$image.png", Modifier.matchParentSize(), ContentScale.Crop) }
                }
                Text(key, color = if (on) c.primaryText else c.text2, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 수준 배지 — 초보가 고를 수 있는 수준(입문·초급·초중급)은 primary 톤, 중급 상한은 경고 톤. 글자는 자료집의 수준 그대로. */
@Composable
internal fun LevelBadge(p: RoutinePreset) {
    val c = Trex.c
    val easy = p.beginnerFriendly
    Text(p.level, color = if (easy) c.primaryText else c.warn, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (easy) c.primaryWash else c.warnWash).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** '자세 교정 지원' 표시 — 카메라 규칙이 있는 종목에만 붙고, 없는 종목에는 아무것도 붙지 않는다(사용자 요청 2026-11-07 — '기록 전용' 꼬리표 제거). */
@Composable
internal fun PostureSupportPill(text: String = "자세 교정 지원") {
    val c = Trex.c
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primaryWash).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Videocam, null, tint = c.primaryText, modifier = Modifier.size(12.dp))
        Text(text, color = c.primaryText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * 루틴 카드 — 왼쪽 부위 그림(수준 배지를 그림 위에), 오른쪽 제목·장비·시간·자세 교정 종목 수·배정 요일. 카드 = 미리 보기, 달력 아이콘 = 요일에 넣기.
 * [showEquipment] 는 장비를 고르지 않았을 때만(같은 제목이 장비마다 있다).
 */
@Composable
internal fun PresetCard(p: RoutinePreset, days: List<String>, showEquipment: Boolean, onPreview: () -> Unit, onAssign: () -> Unit) {
    val c = Trex.c
    Surface(onClick = onPreview, shape = RoundedCornerShape(20.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2)) {
                AssetArtwork("routine/${p.image}.png", Modifier.matchParentSize(), ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                Text(p.minutes, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 19.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(p)
                    if (showEquipment) WashPill(p.equipment)
                    Text("${p.items.size}종목", color = c.text2, fontSize = 11.5.sp)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.postureCount > 0) PostureSupportPill("자세 교정 ${p.postureCount}/${p.items.size}")
                    if (days.isNotEmpty()) Text(days.joinToString(" · "), color = c.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onAssign) { Icon(Icons.Rounded.CalendarMonth, "${p.displayName} 요일에 넣기", tint = if (days.isEmpty()) c.text3 else c.primaryText) }
        }
    }
}
