package com.example.trex_kotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = TrexDark.toArgb()
        window.navigationBarColor = TrexDark.toArgb()

        setContent {
            TrexTheme {
                TrexApp()
            }
        }
    }
}
