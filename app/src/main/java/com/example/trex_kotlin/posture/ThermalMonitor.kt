package com.example.trex_kotlin.posture

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executor

// 열 상태 구독은 Android 전용(§108c) — 정책(InferencePolicy)은 공통 엔진이고, iPhone 은 ProcessInfo.thermalState 를 같은 단계로 옮긴다.

/** PowerManager 열 상태 구독 (API 29+). 그 이하에서는 항상 NONE. */
class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile
    var status: Int = InferencePolicy.THERMAL_NONE
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29 && pm != null

    fun start(executor: Executor, onChange: (Int) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 29) return
        val p = pm ?: return
        status = p.currentThermalStatus
        val l = PowerManager.OnThermalStatusChangedListener { s ->
            status = s
            onChange(s)
        }
        listener = l
        p.addThermalStatusListener(executor, l)
        onChange(status)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < 29) return
        listener?.let { pm?.removeThermalStatusListener(it) }
        listener = null
    }
}
