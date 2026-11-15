package com.tiji.studyclock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen entry point. The receiver is exported because launchers enumerate widgets through
 * it, but it only reacts to system refreshes; state-changing taps go to the private
 * [TimerWidgetActionReceiver] so other apps cannot drive the timer.
 */
class TimerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        runAsync(context) { TimerWidgetRuntime.refresh(context) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> runAsync(context) { TimerWidgetRuntime.refresh(context) }
        }
    }

    private fun runAsync(context: Context, work: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                work()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 状态没有改变时保留上一帧，用户仍可回到应用操作。
            } finally {
                pending.finish()
            }
        }
    }
}
