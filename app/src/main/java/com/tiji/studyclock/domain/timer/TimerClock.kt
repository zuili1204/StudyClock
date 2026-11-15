package com.tiji.studyclock.domain.timer

/**
 * Two clocks are kept on purpose: the wall clock survives a reboot but can be changed by the user,
 * while the monotonic clock cannot be changed but restarts at zero. [TimerStart.bootCount] decides
 * which one is authoritative for a given run.
 */
interface TimerClock {
    fun wallNow(): Long

    fun elapsedNow(): Long
}
