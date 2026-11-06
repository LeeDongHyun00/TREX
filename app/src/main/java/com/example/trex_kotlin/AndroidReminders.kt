package com.example.trex_kotlin

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/*
 * 기기 알림(§131 P16) — Android 짝. 운동 요일 알림은 `setAndAllowWhileIdle`(분 단위 오차 허용), 휴식 끝은 `setAlarmClock`(사용자가 켠 타이머라 정확 알람 허용).
 * 재부팅 뒤 재예약은 아직 없다(다음 앱 실행에서 설정 화면이 다시 잡는다). 서버 푸시가 아니다. iPhone 짝은 ios-ui PlatformUi.kt(아직 없음).
 */

private const val CHANNEL = "trex_reminders"
private const val REQ_REST = 9001
private const val REQ_DAY_BASE = 9100
internal const val EXTRA_KIND = "kind"
internal const val EXTRA_ISO_DAY = "isoDay"
internal const val EXTRA_HOUR = "hour"
internal const val EXTRA_MINUTE = "minute"

@Composable
fun rememberReminderScheduler(): ReminderScheduler {
    val context = LocalContext.current.applicationContext
    var pending: (() -> Unit)? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) pending?.invoke(); pending = null }
    return remember(context) {
        object : ReminderScheduler {
            override val available: Boolean = true
            override fun permitted(): Boolean = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            override fun requestPermission(onGranted: () -> Unit) {
                if (permitted()) onGranted() else { pending = onGranted; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            override fun scheduleWorkoutDays(isoDays: Set<Int>, hour: Int, minute: Int) = Reminders.scheduleWorkoutDays(context, isoDays, hour, minute)
            override fun scheduleRestEnd(atMillis: Long?) = Reminders.scheduleRestEnd(context, atMillis)
        }
    }
}

internal object Reminders {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "운동 알림", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "운동 요일·휴식 끝 알림" })
        }
    }

    private fun intent(context: Context, kind: String, isoDay: Int = 0, hour: Int = 0, minute: Int = 0, request: Int): PendingIntent =
        PendingIntent.getBroadcast(context, request,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ISO_DAY, isoDay).putExtra(EXTRA_HOUR, hour).putExtra(EXTRA_MINUTE, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 요일 1=월 … 7=일. 고르지 않은 요일의 예약은 취소한다. */
    fun scheduleWorkoutDays(context: Context, isoDays: Set<Int>, hour: Int, minute: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (day in 1..7) {
            val pi = intent(context, "day", day, hour, minute, REQ_DAY_BASE + day)
            if (day !in isoDays) { alarms.cancel(pi); continue }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(day, hour, minute), pi)
        }
    }

    /** 다음 그 요일 hour:minute(기기 시간대). 오늘 그 요일이고 시각이 지났으면 다음 주. */
    fun nextOccurrence(isoDay: Int, hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val calendarDay = if (isoDay == 7) Calendar.SUNDAY else isoDay + 1   // Calendar: 일=1 … 토=7
        while (cal.get(Calendar.DAY_OF_WEEK) != calendarDay || cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun scheduleRestEnd(context: Context, atMillis: Long?) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(context, "rest", request = REQ_REST)
        if (atMillis == null || atMillis <= System.currentTimeMillis() + 1500) { alarms.cancel(pi); return }
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, null), pi)
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.trex_dino_head).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}

/** 알람이 울리면 알림을 띄우고, 운동 요일 알림은 다음 주 같은 시각으로 다시 건다. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_KIND)) {
            "rest" -> Reminders.notify(context, REQ_REST, "휴식 끝", "다음 세트를 시작할 시간이에요")
            "day" -> {
                val day = intent.getIntExtra(EXTRA_ISO_DAY, 0); val hour = intent.getIntExtra(EXTRA_HOUR, 19); val minute = intent.getIntExtra(EXTRA_MINUTE, 30)
                Reminders.notify(context, REQ_DAY_BASE + day, "오늘 운동 요일이에요", "주간 루틴이 기다리고 있어요")
                if (day in 1..7) Reminders.scheduleWorkoutDays(context, setOf(day), hour, minute)
            }
        }
    }
}
