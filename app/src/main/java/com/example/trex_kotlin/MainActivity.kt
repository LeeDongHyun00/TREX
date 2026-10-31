package com.example.trex_kotlin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.trex_kotlin.security.LocalDataSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.trex_kotlin.routines.AppLinkInbox

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 공유 링크의 "앱에서 열기"(trex://r/…, trex://p/…)로 켜졌으면 그 주소를 공유 화면에 넘긴다 — 회전 등으로 다시 만들 때는 넘기지 않는다
        if (savedInstanceState == null) AppLinkInbox.deliver(intent?.data?.toString())
        setContent {
            var attempt by remember { mutableIntStateOf(0) }
            var status by remember { mutableIntStateOf(0) }
            LaunchedEffect(attempt) {
                status = 0
                status = withContext(Dispatchers.IO) {
                    runCatching { LocalDataSecurity.prepare(applicationContext) }.fold({ 1 }, { 2 })
                }
            }
            if (status == 1) TrexApp() else MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (status == 0) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(20.dp))
                            Text("기존 기록을 안전하게 준비하고 있습니다.")
                        } else {
                            Text("기록을 열지 못했습니다. 데이터는 초기화하지 않았습니다. 앱을 삭제하거나 앱 데이터를 지우지 말고 다시 시도해 주세요.")
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { attempt++ }) { Text("다시 시도") }
                        }
                    }
                }
            }
        }
    }

    /** 이미 켜져 있을 때 링크가 오면(launchMode singleTask) 여기로 온다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLinkInbox.deliver(intent.data?.toString())
    }
}
