package com.example.trex_kotlin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.trex_kotlin.routines.AppLinkInbox

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 공유 링크의 "앱에서 열기"(trex://r/…, trex://p/…)로 켜졌으면 그 주소를 공유 화면에 넘긴다 — 회전 등으로 다시 만들 때는 넘기지 않는다
        if (savedInstanceState == null) AppLinkInbox.deliver(intent?.data?.toString())
        setContent {
            TrexApp()
        }
    }

    /** 이미 켜져 있을 때 링크가 오면(launchMode singleTask) 여기로 온다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLinkInbox.deliver(intent.data?.toString())
    }
}
