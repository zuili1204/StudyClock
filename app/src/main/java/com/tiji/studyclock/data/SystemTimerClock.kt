package com.tiji.studyclock.data

import android.os.SystemClock
import com.tiji.studyclock.domain.timer.TimerClock

/** Wall clock for display and reboot spans; monotonic clock while the device stays up. */
object SystemTimerClock : TimerClock {
    override fun wallNow(): Long = System.currentTimeMillis()

    override fun elapsedNow(): Long = SystemClock.elapsedRealtime()
}
