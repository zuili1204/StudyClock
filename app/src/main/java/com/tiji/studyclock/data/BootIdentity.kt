package com.tiji.studyclock.data

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies the current boot. The monotonic clock restarts at zero after a reboot, so a stored
 * timer start can only be measured monotonically while this value still matches.
 */
@Singleton
class BootIdentity @Inject constructor(@ApplicationContext context: Context) {
    val current: Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
}
