package com.tiji.studyclock

import android.app.Application
import com.tiji.studyclock.widget.TimerWidgetSync
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StudyClockApplication : Application() {
    @Inject lateinit var timerWidgetSync: TimerWidgetSync

    override fun onCreate() {
        super.onCreate()
        timerWidgetSync.start()
    }
}
