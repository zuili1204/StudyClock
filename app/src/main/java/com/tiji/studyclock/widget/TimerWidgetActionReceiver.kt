package com.tiji.studyclock.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles widget taps; not exported, so only our own pending intents can start or stop a timer. */
class TimerWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                TimerWidgetRuntime.perform(context, action)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 计时状态未变，保留当前画面。
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.tiji.studyclock.widget.START"
        const val ACTION_PAUSE = "com.tiji.studyclock.widget.PAUSE"
        const val ACTION_RESUME = "com.tiji.studyclock.widget.RESUME"
        const val ACTION_FINISH = "com.tiji.studyclock.widget.FINISH"
    }
}
