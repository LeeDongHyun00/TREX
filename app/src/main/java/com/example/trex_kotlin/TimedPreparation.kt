package com.example.trex_kotlin

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** 카메라를 사용하지 않는 운동도 준비 시간을 제공한다. 목표 시간과는 별개다. */
@Composable
internal fun TimedPreparationScreen(step: SessionStep, paused: Boolean, onTogglePause: () -> Unit,
    onNext: () -> Unit, onExit: () -> Unit) {
    val c=Trex.c
    var remaining by remember(step.token) { mutableLongStateOf(5000L) }
    var total by remember(step.token) { mutableLongStateOf(5000L) }
    var delivered by remember(step.token) { mutableStateOf(false) }
    val isPaused by rememberUpdatedState(paused)
    val next by rememberUpdatedState(onNext)
    fun finish() { if(!delivered) { delivered=true;next() } }
    LaunchedEffect(step.token,paused) {
        var last=SystemClock.elapsedRealtime()
        while(!delivered) {
            delay(100);val now=SystemClock.elapsedRealtime()
            if(!isPaused)remaining=(remaining-(now-last)).coerceAtLeast(0)
            last=now
            if(remaining==0L)finish()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=24.dp,vertical=22.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Text("운동 준비",color=c.text2,fontSize=14.sp)
        Text(step.workout.name,color=c.text,fontSize=28.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp))
        Text("${step.setLabel} · ${step.workout.repsSpec().targetLabel}",color=c.text2,modifier=Modifier.padding(top=8.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            RingGauge(1f-remaining.toFloat()/total,224.dp,9.dp) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("${ceil(remaining/1000.0).toInt()}",color=c.text,fontSize=52.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(paused)"일시정지" else "시작까지",color=c.text2,fontSize=13.sp)
                }
            }
            Text("편안하게 자리를 잡아 주세요.",color=c.text2,modifier=Modifier.padding(top=24.dp))
            TextButton(onClick={remaining=(remaining+5000).coerceAtMost(30000);total=maxOf(total,remaining)}) {
                Text("준비 시간 +5초",color=c.primaryText)
            }
        }
        TextButton(onClick=onExit) { Text("나가기",color=c.text2) }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            GhostButton(if(paused)"재개" else "일시정지",onTogglePause,Modifier.weight(1f))
            Cta("바로 시작",{finish()},Modifier.weight(1.8f),enabled=!paused && !delivered)
        }
    }
}
