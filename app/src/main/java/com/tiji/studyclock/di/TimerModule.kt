package com.tiji.studyclock.di

import com.tiji.studyclock.data.ApplicationScope
import com.tiji.studyclock.data.BootIdentity
import com.tiji.studyclock.data.SystemTimerClock
import com.tiji.studyclock.data.repo.TimerRepository
import com.tiji.studyclock.domain.timer.TimerClock
import com.tiji.studyclock.domain.timer.TimerController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object TimerModule {
    @Provides
    @Singleton
    fun provideTimerClock(): TimerClock = SystemTimerClock

    @Provides
    @Singleton
    fun provideTimerController(
        store: TimerRepository,
        clock: TimerClock,
        bootIdentity: BootIdentity,
        @ApplicationScope scope: CoroutineScope,
    ): TimerController = TimerController(store, clock, bootIdentity.current, scope)
}
