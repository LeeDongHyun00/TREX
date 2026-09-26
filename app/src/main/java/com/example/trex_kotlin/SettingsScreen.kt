package com.example.trex_kotlin

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 익숙한 설정 목록에서 각 기능으로 바로 들어간다. 상단 요약에도 편집 경로가 있다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTabScreen(app: AppViewModel, onOpenRecord: () -> Unit, onLogout: () -> Unit) {
    val c = Trex.c
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<String?>(null) }
    val p = app.profile
    LazyColumn(Modifier.fillMaxSize(), contentPadding = tabContentPadding, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("설정", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Surface(onClick = { sheet = "profile" }, shape = RoundedCornerShape(22.dp), color = c.surface) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = c.primaryWash) {
                        Icon(Icons.Rounded.Person, null, tint = c.primaryText, modifier = Modifier.padding(14.dp).size(26.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("프로필 편집", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${p.heightCm.toInt()}cm · ${p.weightKg}kg · ${p.age}세", color = c.text2, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp))
                        Text(profileGoalLabel(p.goal), color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(20.dp))
                }
            }
        }
        item { SettingsGroup {
            SettingsRow("운동 기록", Icons.Rounded.History, onClick = onOpenRecord)
        } }
        item { SettingsGroup {
            SettingsRow("화면 모드", Icons.Rounded.Brightness4,
                when(app.themeMode) { ThemeMode.Light -> "라이트"; ThemeMode.Dark -> "다크"; ThemeMode.System -> "시스템" }, { sheet = "theme" })
            SettingsDivider()
            SettingsRow("알림", Icons.Rounded.NotificationsNone, onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            SettingsDivider()
            SettingsRow("카메라 및 권한", Icons.Rounded.PrivacyTip, onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            })
        } }
        item { SettingsGroup {
            SettingsRow("도움말", Icons.Rounded.HelpOutline, onClick = { sheet = "help" })
            SettingsDivider()
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("버전", color = c.text, fontSize = 16.sp); Text("1.0", color = c.text2, fontSize = 15.sp)
            }
        } }
        item { Surface(onClick = { sheet = "logout" }, color = c.surface, shape = RoundedCornerShape(20.dp)) {
            Text("로그아웃", color = c.err, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(20.dp))
        } }
    }
    if (sheet == "profile") ProfileEditSheet(app, { sheet = null })
    if (sheet == "theme") ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = c.sheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("화면 모드", color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            listOf(ThemeMode.Light to "라이트", ThemeMode.Dark to "다크", ThemeMode.System to "시스템").forEach { (mode, label) ->
                Row(Modifier.fillMaxWidth().clickable { app.setTheme(mode) }.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (app.themeMode == mode) Icon(Icons.Rounded.Check, null, tint = c.primaryText)
                }
            }
            Cta("완료", { sheet = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (sheet == "help") AlertDialog(onDismissRequest = { sheet = null }, containerColor = c.sheet,
        title = { Text("자세 비교 사용하기") },
        text = { Text("운동 이름 옆 스위치로 자세 비교를 켜세요. 시작 전에 안내하는 촬영 방향으로 전신을 담아 주세요.\n\n횟수가 다르게 측정되면 운동 화면의 ‘횟수 수정’에서 조정할 수 있어요.") },
        confirmButton = { TextButton(onClick = { sheet = null }) { Text("확인") } })
    if (sheet == "logout") AlertDialog(onDismissRequest = { sheet = null }, containerColor = c.sheet,
        title = { Text("로그아웃할까요?") }, text = { Text("이 기기의 운동 기록은 유지돼요.") },
        dismissButton = { TextButton(onClick = { sheet = null }) { Text("취소") } },
        confirmButton = { TextButton(onClick = { sheet = null; onLogout() }) { Text("로그아웃", color = c.err) } })
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Trex.c.surface) { Column(Modifier.fillMaxWidth(), content = content) }
}
@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 54.dp), color = Trex.c.line) }
@Composable
private fun SettingsRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, onClick: () -> Unit) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.primaryText, modifier = Modifier.size(22.dp))
        Text(title, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
        value?.let { Text(it, color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp)) }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val original = remember { app.profile }
    var height by remember { mutableStateOf(original.heightCm.toString()) }
    var weight by remember { mutableStateOf(original.weightKg.toString()) }
    var age by remember { mutableStateOf(original.age.toString()) }
    var goal by remember { mutableStateOf(original.goal) }
    val valid = height.toDoubleOrNull()?.let { it in 80.0..250.0 } == true &&
        weight.toDoubleOrNull()?.let { it in 20.0..350.0 } == true && age.toIntOrNull()?.let { it in 10..110 } == true
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.sheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 570.dp).imePadding().padding(horizontal = 24.dp).padding(bottom = 22.dp)) {
            Text("프로필 편집", color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DField(height, { height = it.decimalOnly() }, "키", label = "키 · cm", keyboardType = KeyboardType.Decimal)
                DField(weight, { weight = it.decimalOnly() }, "몸무게", label = "몸무게 · kg", keyboardType = KeyboardType.Decimal)
                DField(age, { age = it.digitsOnly() }, "나이", label = "나이", keyboardType = KeyboardType.Number)
                Text("운동 목표", color = c.text2, fontSize = 13.sp)
                val goals = listOf("general", "diet", "muscle", "stamina", "maintain")
                FilterChipRow(goals.map(::profileGoalLabel), profileGoalLabel(goal), { label -> goal = goals.first { profileGoalLabel(it) == label } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("취소", onClose, Modifier.weight(1f))
                Cta("저장", { app.completeOnboarding(original.copy(heightCm = height.toDouble(), weightKg = weight.toDouble(), age = age.toInt(), goal = goal)); onClose() },
                    enabled = valid, modifier = Modifier.weight(1.7f))
            }
        }
    }
}
