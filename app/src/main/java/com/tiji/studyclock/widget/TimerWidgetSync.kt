package com.tiji.studyclock.widget

import android.content.Context
import com.tiji.studyclock.data.ApplicationScope
import com.tiji.studyclock.domain.timer.TimerController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pushes every stored-timer change to the home-screen widget while the process is alive.
 * A killed process cannot update the widget, so the widget is re-rendered on the next app start,
 * boot or launcher refresh instead of being kept in sync by a background poller.
 */
@Singleton
class TimerWidgetSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timerController: TimerController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            try {
                timerController.timers.collect { TimerWidgetRuntime.refresh(context) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 小组件刷新失败不影响计时与记录。
            }
        }
    }
}
