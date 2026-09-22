package com.example.trex_kotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            if (BuildConfig.VALIDATION_STUDIO) com.example.trex_kotlin.validation.ValidationStudio() else TrexApp()
        }
    }
}
