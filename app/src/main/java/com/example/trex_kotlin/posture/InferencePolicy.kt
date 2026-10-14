package com.example.trex_kotlin.posture

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executor

/**
 * 추론 스케줄링 정책 — 발열의 1차 원인(쉬지 않는 추론)을 구조적으로 막는다.
 *
 * 판정(피처·규칙·카운터·로그)은 300 ms 격자의 프레임만 받는다(§96, `PostureLive` 의 SESSION_SAMPLE_INTERVAL_MS). 추론 자체는 화면(뼈대)을 위해 더 빠를 수 있고
 * (§94, 85 ms), 그 비용은 발열이라 아래 배수가 막는다.
 *  - RECORDING: 화면 추론 간격(sampleIntervalMs)
 *  - IDLE     : 프레이밍 확인용 저속 오버레이
 *  - RESULT   : 리포트를 읽는 동안 거의 정지 (배지 갱신용 최소)
 * 여기에 OS 열 상태(PowerManager)에 따른 배수를 곱해 자동 감속한다. 배수는 세트 창 통계의 의미를 해치지 않는 범위
 * (연구 기준 창 = 여러 렙에 걸친 16프레임; 10초 세트에서 1.7 fps 여도 17프레임) 로 제한한다.
 */
enum class InferencePhase { IDLE, RECORDING, RESULT }

data class InferencePolicy(
    val sampleIntervalMs: Long = 300L,
    val idleIntervalMs: Long = 400L,
    val resultIntervalMs: Long = 1500L,
) {
    fun baseIntervalMs(phase: InferencePhase): Long = when (phase) {
        InferencePhase.RECORDING -> sampleIntervalMs
        InferencePhase.IDLE -> idleIntervalMs
        InferencePhase.RESULT -> resultIntervalMs
    }

    /** OS 열 상태(PowerManager.THERMAL_STATUS_*) → 간격 배수. */
    fun thermalMultiplier(thermalStatus: Int): Float = when {
        thermalStatus >= THERMAL_CRITICAL -> 3.0f
        thermalStatus == THERMAL_SEVERE -> 2.0f
        thermalStatus == THERMAL_MODERATE -> 1.5f
        else -> 1.0f
    }

    fun intervalMs(phase: InferencePhase, thermalStatus: Int): Long =
        (baseIntervalMs(phase) * thermalMultiplier(thermalStatus)).toLong()

    /** 이번 프레임을 추론할지. lastInferAtMs 는 마지막 추론 시각(0 = 없음). */
    fun shouldInfer(nowMs: Long, lastInferAtMs: Long, phase: InferencePhase, thermalStatus: Int): Boolean =
        lastInferAtMs == 0L || nowMs - lastInferAtMs >= intervalMs(phase, thermalStatus)

    companion object {
        // PowerManager.THERMAL_STATUS_* (API 29). 상수값을 직접 써서 API 26 에서도 참조 가능하게 한다.
        const val THERMAL_NONE = 0
        const val THERMAL_LIGHT = 1
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
        const val THERMAL_CRITICAL = 4
        const val THERMAL_EMERGENCY = 5
        const val THERMAL_SHUTDOWN = 6

        fun thermalLabel(status: Int): String = when (status) {
            THERMAL_NONE -> "정상"
            THERMAL_LIGHT -> "약간 높음"
            THERMAL_MODERATE -> "보통 발열"
            THERMAL_SEVERE -> "심한 발열"
            THERMAL_CRITICAL -> "위험"
            THERMAL_EMERGENCY -> "긴급"
            THERMAL_SHUTDOWN -> "종료 임박"
            else -> "알 수 없음"
        }
    }
}

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
