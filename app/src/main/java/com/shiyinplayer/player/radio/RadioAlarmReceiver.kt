package com.shiyinplayer.player.radio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * 闹钟定时播放广播接收器。
 *
 * 闹钟触发时，读取上次播放的电台（DataStore lastRadioUrl），调用 RadioPlayer 播放。
 * 每次触发后自动注册下一天的闹钟。
 */
@AndroidEntryPoint
class RadioAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RadioAlarmReceiver"
        const val ACTION_ALARM = "com.shiyinplayer.RADIO_ALARM"
        private const val REQUEST_CODE = 20240

        /**
         * 注册/取消闹钟。
         * @param enabled 是否启用
         * @param hour 小时 (0-23)
         * @param minute 分钟 (0-59)
         */
        fun schedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RadioAlarmReceiver::class.java).apply {
                action = ACTION_ALARM
            }
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (!enabled) {
                alarmManager.cancel(pi)
                pi.cancel()
                Log.i(TAG, "闹钟已取消")
                return
            }

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // 如果时间已过，设为明天
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            // setAlarmClock 在所有 Android 版本上都精确且不受 Doze 影响
            val alarmInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pi)
            alarmManager.setAlarmClock(alarmInfo, pi)
            Log.i(TAG, "闹钟已设置: ${calendar.time}")
        }
    }

    @Inject lateinit var radioPlayer: RadioPlayer
    @Inject lateinit var settingsRepository: com.shiyinplayer.ui.settings.SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ALARM) return
        Log.i(TAG, "闹钟触发！")

        val pendingResult = goAsync()

        scope.launch {
            try {
                val url = settingsRepository.lastRadioUrlSync()
                val name = settingsRepository.lastRadioNameSync()
                val id = settingsRepository.lastRadioIdSync()

                if (url.isBlank()) {
                    Log.w(TAG, "无上次电台记录，跳过")
                    return@launch
                }

                Log.i(TAG, "播放电台: $name ($url)")
                radioPlayer.playStation(
                    url = url,
                    stationId = if (id > 0) id else null,
                    stationName = name,
                    autoPlay = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "闹钟播放失败", e)
            } finally {
                // 闹钟是一次性的，重新注册明天的闹钟
                rescheduleNext(context)
                pendingResult.finish()
            }
        }
    }

    private fun rescheduleNext(context: Context) {
        val enabled = settingsRepository.radioAlarmEnabledSync()
        if (!enabled) return
        val hour = settingsRepository.radioAlarmHourSync()
        val minute = settingsRepository.radioAlarmMinuteSync()
        schedule(context, true, hour, minute)
    }
}
