package com.example.trex_kotlin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.trex_kotlin.sync.AppleWebSignIn

/**
 * Apple 웹 로그인(Android)이 돌아오는 문 — 서버 콜백이 연 딥링크(`trexapple://callback?…`)를 [AppleWebSignIn] 에 넘기고,
 * 로그인을 누른 MainActivity 로 돌아간다(CLEAR_TOP 이 그 위의 Custom Tab 을 닫는다). 화면은 그리지 않는다.
 */
class AppleSignInCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let(AppleWebSignIn::deliver)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
