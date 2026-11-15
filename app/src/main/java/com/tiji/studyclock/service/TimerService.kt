package com.tiji.studyclock.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tiji.studyclock.MainActivity
import com.tiji.studyclock.R
import com.tiji.studyclock.data.BootIdentity
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.domain.model.Category
import com.tiji.studyclock.domain.timer.SessionTimer
import com.tiji.studyclock.domain.timer.TimerController
import com.tiji.studyclock.domain.timer.TimerNotice
import com.tiji.studyclock.domain.timer.TimerNoticeFactory
import com.tiji.studyclock.domain.timer.TimerStart
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the running practice timer visible on the lock screen and in the notification shade.
 * The service owns no timer state: it mirrors [TimerController.timers], forwards notification
 * actions to the controller and stops itself when the stored timer disappears, so process death
 * or a reboot never leaves a stale notification behind.
 */
@AndroidEntryPoint
class TimerService : Service() {
    @Inject lateinit var timerController: TimerController
    @Inject lateinit var categoryRepository: CategoryRepository
    @Inject lateinit var bootIdentity: BootIdentity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdownJob: Job? = null
    private var lastSnapshot: TimerSnapshot? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 前台服务必须在五秒内挂出通知；真实内容随数据流到达后立即替换。
        ServiceCompat.startForeground(this, NOTIFICATION_ID, placeholderNotification(), foregroundServiceType())
        scope.launch {
            try {
                combine(
                    timerController.timers,
                    timerController.loaded,
                    categoryRepository.subjects,
                    categoryRepository.categories,
                ) { timers, loaded, subjects, categories ->
                    TimerSnapshot(
                        timer = timers.active,
                        subjectName = subjects.firstOrNull { it.id == timers.active?.subjectId }?.name,
                        categories = categories,
                        loaded = loaded,
                    )
                }.distinctUntilChanged().collect { snapshot ->
                    lastSnapshot = snapshot
                    val timer = snapshot.timer
                    when {
                        timer != null -> updateNotification(timer, snapshot.subjectName, snapshot.categories)
                        // 进程被系统重建后先等首帧读取结果，否则会把仍在进行的计时提前停掉。
                        !snapshot.loaded -> Unit
                        else -> {
                            ServiceCompat.stopForeground(this@TimerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ServiceCompat.stopForeground(this@TimerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> perform { timerController.pause() }
            ACTION_RESUME -> perform { timerController.resume() }
            ACTION_FINISH -> perform { timerController.finish() }
            // 通知权限刚授予时系统不会补发先前被丢弃的那条，这里按当前状态再挂一次。
            ACTION_REFRESH -> lastSnapshot?.let { snapshot ->
                snapshot.timer?.let { updateNotification(it, snapshot.subjectName, snapshot.categories) }
            }
        }
        return START_STICKY
    }

    private fun perform(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // 状态未变，通知继续显示原状态；用户可回到应用重试。
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(timer: TimerStart, subjectName: String?, categories: List<Category>) {
        val nowAt = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val notice = TimerNoticeFactory.build(
            start = timer,
            subjectName = subjectName,
            categoryName = categories.firstOrNull { it.id == timer.categoryId }?.name,
            nowAt = nowAt,
            nowElapsed = nowElapsed,
            currentBootCount = bootIdentity.current,
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(notice, timer))
        scheduleCountdownArrival(timer, subjectName, categories, nowAt, nowElapsed)
    }

    /** 倒计时归零时只刷新通知文案；是否自动结算由计时流程决定，避免服务越权落库。 */
    private fun scheduleCountdownArrival(
        timer: TimerStart,
        subjectName: String?,
        categories: List<Category>,
        nowAt: Long,
        nowElapsed: Long,
    ) {
        countdownJob?.cancel()
        val remaining = SessionTimer.remainingSeconds(timer, nowAt, nowElapsed, bootIdentity.current)
            ?: return
        if (timer.isPaused || remaining <= 0) return
        countdownJob = scope.launch {
            delay(remaining * 1000L)
            updateNotification(timer, subjectName, categories)
        }
    }

    private fun buildNotification(notice: TimerNotice, timer: TimerStart): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSubText(notice.subText)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (timer.isPaused) {
            builder.addAction(android.R.drawable.ic_media_play, "继续", serviceAction(ACTION_RESUME, 2))
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", serviceAction(ACTION_PAUSE, 1))
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束", serviceAction(ACTION_FINISH, 3))
        val base = notice.chronometerBaseMillis
        if (base != null) {
            builder.setWhen(base)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(notice.countDown)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle("正在读取本次计时")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TimerService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "计时进行中", NotificationManager.IMPORTANCE_LOW).apply {
            description = "在锁屏和通知栏显示正在进行的计时"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        private const val CHANNEL_ID = "timer_running"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PAUSE = "com.tiji.studyclock.action.PAUSE_TIMER"
        private const val ACTION_RESUME = "com.tiji.studyclock.action.RESUME_TIMER"
        private const val ACTION_FINISH = "com.tiji.studyclock.action.FINISH_TIMER"
        private const val ACTION_REFRESH = "com.tiji.studyclock.action.REFRESH_TIMER"

        private data class TimerSnapshot(
            val timer: TimerStart?,
            val subjectName: String?,
            val categories: List<Category>,
            val loaded: Boolean,
        )

        /** 应用在前台时调用；系统拒绝后台启动时计时本身不受影响，只是没有常驻通知。 */
        fun start(context: Context) = launch(context, action = null)

        /** 授权或状态变化后重新挂出通知；计时未开始时服务会自行退出。 */
        fun refresh(context: Context) = launch(context, ACTION_REFRESH)

        private fun launch(context: Context, action: String?) {
            try {
                val intent = Intent(context, TimerService::class.java)
                if (action != null) intent.action = action
                ContextCompat.startForegroundService(context, intent)
            } catch (_: IllegalStateException) {
                // Foreground-service start restrictions: the timer still runs, only the notice is missing.
            }
        }
    }
}
