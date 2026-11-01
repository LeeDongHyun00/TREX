@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin.posture

import androidx.compose.runtime.Composable
import com.example.trex_kotlin.ios.appendTextFile
import com.example.trex_kotlin.ios.readSecureTextFile
import com.example.trex_kotlin.ios.secureFiles
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.utcDayStamp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * 자세 패키지의 iPhone 쪽 짝(§108c) — Android `posture/SetLogStore.kt` 와 같은 이름·같은 시그니처.
 * 세트 로그 형식(SetLog·SetLogJson)은 공유 PostureSetLog.kt 그대로이고, 여기는 파일 자리와 숫자 고정 소수만 맡는다.
 */

/**
 * 세트 로그 숫자의 고정 소수 — Android `String.format(Locale.US, "%.Nf", v)` 와 같은 규칙: 값(float 를 double 로 넓힌 것)의
 * 가장 짧은 십진 표현에서 소수 [decimals] 자리 반올림(HALF_UP). 지역화·과학적 표기 없음. NaN·무한은 호출부가 먼저 거른다.
 */
internal fun formatFixed(v: Float, decimals: Int): String {
    val d = v.toDouble()
    if (d.isNaN() || d.isInfinite()) return d.toString()
    val negative = d < 0.0 || (d == 0.0 && 1.0 / d < 0.0)
    val text = kotlin.math.abs(d).toString()
    val mantissa = text.substringBefore('E')
    val exponent = if ('E' in text) text.substringAfter('E').toInt() else 0
    var digits = mantissa.substringBefore('.') + mantissa.substringAfter('.', "")
    var point = mantissa.substringBefore('.').length + exponent
    if (point < 0) { digits = "0".repeat(-point) + digits; point = 0 }
    if (point > digits.length) digits += "0".repeat(point - digits.length)
    val keep = point + decimals
    val kept = StringBuilder(if (keep <= digits.length) digits.substring(0, keep) else digits + "0".repeat(keep - digits.length))
    if (keep < digits.length && digits[keep] >= '5') {
        var i = kept.length - 1
        while (i >= 0 && kept[i] == '9') { kept[i] = '0'; i-- }
        if (i >= 0) kept[i] = kept[i] + 1 else { kept.insert(0, '1'); point++ }
    }
    val whole = kept.substring(0, point).trimStart('0').ifEmpty { "0" }
    val fraction = kept.substring(point)
    return (if (negative) "-" else "") + whole + (if (decimals > 0) ".$fraction" else "")
}

/**
 * 세트 로그 저장소 — `Documents/posture_logs/sets-yyyyMMdd.jsonl`(UTC 날짜)에 한 줄씩. Android 의 앱 외부 폴더 자리이고,
 * 실제 파일은 `.aes` 암호문이다(§125). 외부 분석에는 명시적인 복호화 내보내기가 필요하다.
 */
class SetLogStore(private val dir: String) {
    fun append(log: SetLog, now: Long = nowMillis()): String {
        val file = "$dir/sets-${utcDayStamp(now)}.jsonl"
        appendTextFile(file, SetLogJson.encode(log) + "\n")
        return file
    }

    fun files(): List<String> = secureFiles(dir).filter { it.endsWith(".jsonl") }

    fun totalSets(): Int = files().sumOf { f -> readSecureTextFile(f)?.lineSequence()?.count { it.isNotBlank() } ?: 0 }

    companion object {
        /** 세트 로그 쓰기 전용 직렬 실행(앱 수명) — Android 의 단일 기록 스레드처럼 화면 수명과 무관하게 순서대로 쓴다. */
        val writer = SerialWriter()
    }
}

/** 순서대로 하나씩 도는 백그라운드 실행기 — Android `ExecutorService.execute` 자리. */
class SerialWriter {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    fun execute(block: () -> Unit) { scope.launch { block() } }
}

/** 개발용 자세 실험실은 Android 연구 도구다(도달하지 않는 경로). */
@Composable
fun PostureLabScreen(onClose: () -> Unit) {}

/** 개발용 기준선 수집 안내는 Android 연구 도구다(도달하지 않는 경로). */
@Composable
fun BaselineGuideScreen(onClose: () -> Unit) {}
