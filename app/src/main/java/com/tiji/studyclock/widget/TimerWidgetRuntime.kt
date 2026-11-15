package com.tiji.studyclock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.tiji.studyclock.MainActivity
import com.tiji.studyclock.R
import com.tiji.studyclock.data.BootIdentity
import com.tiji.studyclock.data.repo.CategoryRepository
import com.tiji.studyclock.data.repo.SessionRepository
import com.tiji.studyclock.data.repo.SettingsRepository
import com.tiji.studyclock.domain.timer.TimerController
import com.tiji.studyclock.domain.timer.TimerWidgetState
import com.tiji.studyclock.domain.timer.TimerWidgetStates
import com.tiji.studyclock.domain.timer.WidgetAction
import com.tiji.studyclock.service.TimerService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * All widget work lives here so both the (exported) widget provider and the (private) click
 * receiver share one render path. The widget owns no timer state: every change goes through
 * [TimerController], and a running countdown is a platform `Chronometer` anchored to
 * `elapsedRealtime`, so it keeps ticking correctly without a per-second polling job.
 */
internal object TimerWidgetRuntime {
    suspend fun refresh(context: Context) {
        val entry = dependencies(context) ?: return
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TimerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val timers = entry.timerController.timers.first()
        val categories = entry.categoryRepository.categories.first()
        val subjects = entry.categoryRepository.subjects.first()
        val activeCategory = categories.firstOrNull { it.id == timers.active?.categoryId }
        val finishedCategory = categories.firstOrNull { it.id == timers.finished?.categoryId }
        val state = TimerWidgetStates.build(
            active = timers.active,
            finished = timers.finished,
            subjectName = subjects.firstOrNull { it.id == (activeCategory ?: finishedCategory)?.subjectId }?.name,
            categoryName = (activeCategory ?: finishedCategory)?.name,
            nowAt = System.currentTimeMillis(),
            nowElapsed = SystemClock.elapsedRealtime(),
            bootCount = entry.bootIdentity.current,
        )
        ids.forEach { id -> manager.updateAppWidget(id, views(context, id, state)) }
    }

    suspend fun perform(context: Context, action: String) {
        val entry = dependencies(context) ?: return
        when (action) {
            TimerWidgetActionReceiver.ACTION_START -> startDefaultTimer(context, entry)
            TimerWidgetActionReceiver.ACTION_PAUSE -> entry.timerController.pause()
            TimerWidgetActionReceiver.ACTION_RESUME -> entry.timerController.resume()
            TimerWidgetActionReceiver.ACTION_FINISH -> entry.timerController.finish()
        }
        refresh(context)
    }

    /**
     * One tap starts a 25-minute countdown in the current exam, reusing the category of the most
     * recent record so the widget cannot silently switch subjects. An empty exam stops here and
     * the user can still open the app from the widget body.
     */
    private suspend fun startDefaultTimer(context: Context, entry: WidgetDependencies) {
        if (entry.timerController.timers.first().active != null) return
        val exam = entry.settingsRepository.currentExam.first()
        val subjectIds = entry.categoryRepository.subjects.first()
            .filter { it.examType == exam && !it.archived }
            .map { it.id }
            .toSet()
        val categories = entry.categoryRepository.categories.first()
            .filter { it.subjectId in subjectIds && !it.archived }
        if (categories.isEmpty()) return
        val categoryIds = categories.map { it.id }.toSet()
        val lastCategoryId = entry.sessionRepository.sessions.first()
            .filter { it.categoryId in categoryIds }
            .maxByOrNull { it.startAt }
            ?.categoryId
        val category = categories.firstOrNull { it.id == lastCategoryId } ?: categories.first()
        if (entry.timerController.start(
                subjectId = category.subjectId,
                categoryId = category.id,
                targetSec = TimerWidgetStates.DEFAULT_MINUTES * 60,
            ) == null
        ) return
        TimerService.start(context)
    }

    private fun views(context: Context, widgetId: Int, state: TimerWidgetState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_timer).apply {
            setTextViewText(R.id.widget_title, state.title)
            setTextViewText(R.id.widget_subtitle, state.subtitle)
            val live = state.chronometerBaseMillis
            setViewVisibility(R.id.widget_chronometer, if (live != null) View.VISIBLE else View.GONE)
            if (live != null) {
                setChronometer(R.id.widget_chronometer, live, if (state.countDown) "剩余 %s" else "%s", true)
                setChronometerCountDown(R.id.widget_chronometer, state.countDown)
            }
            setViewVisibility(R.id.widget_time, if (live == null && state.staticTime != null) View.VISIBLE else View.GONE)
            if (live == null && state.staticTime != null) {
                setTextViewText(R.id.widget_time, state.staticTime)
            }
            setTextViewText(R.id.widget_primary, state.primaryLabel)
            setOnClickPendingIntent(R.id.widget_primary, intentFor(context, widgetId, state.primary))
            val secondary = state.secondary
            setViewVisibility(R.id.widget_secondary, if (secondary != null) View.VISIBLE else View.GONE)
            if (secondary != null) {
                setTextViewText(R.id.widget_secondary, state.secondaryLabel.orEmpty())
                setOnClickPendingIntent(R.id.widget_secondary, intentFor(context, widgetId, secondary))
            }
        }

    private fun intentFor(context: Context, widgetId: Int, action: WidgetAction): PendingIntent =
        when (action) {
            WidgetAction.OPEN_TIMER -> openApp(context, widgetId, TimerWidgetStates.ROUTE_TIMER)
            WidgetAction.OPEN_CAPTURE -> openApp(context, widgetId, TimerWidgetStates.ROUTE_CAPTURE)
            else -> {
                val intent = Intent(context, TimerWidgetActionReceiver::class.java)
                    .setAction(
                        when (action) {
                            WidgetAction.START -> TimerWidgetActionReceiver.ACTION_START
                            WidgetAction.PAUSE -> TimerWidgetActionReceiver.ACTION_PAUSE
                            WidgetAction.RESUME -> TimerWidgetActionReceiver.ACTION_RESUME
                            else -> TimerWidgetActionReceiver.ACTION_FINISH
                        },
                    )
                PendingIntent.getBroadcast(
                    context,
                    widgetId * 20 + action.ordinal,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

    private fun openApp(context: Context, widgetId: Int, route: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
        }
        // Extras do not participate in PendingIntent identity: each route needs its own
        // request code, or FLAG_UPDATE_CURRENT hands both buttons the last route.
        val routeSlot = if (route == TimerWidgetStates.ROUTE_CAPTURE) 18 else 19
        return PendingIntent.getActivity(
            context,
            widgetId * 20 + routeSlot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dependencies(context: Context): WidgetDependencies? = runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TimerWidgetEntryPoint::class.java,
        ).let { entry ->
            WidgetDependencies(
                timerController = entry.timerController(),
                categoryRepository = entry.categoryRepository(),
                sessionRepository = entry.sessionRepository(),
                settingsRepository = entry.settingsRepository(),
                bootIdentity = entry.bootIdentity(),
            )
        }
    }.getOrNull()
}

internal class WidgetDependencies(
    val timerController: TimerController,
    val categoryRepository: CategoryRepository,
    val sessionRepository: SessionRepository,
    val settingsRepository: SettingsRepository,
    val bootIdentity: BootIdentity,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TimerWidgetEntryPoint {
    fun timerController(): TimerController
    fun categoryRepository(): CategoryRepository
    fun sessionRepository(): SessionRepository
    fun settingsRepository(): SettingsRepository
    fun bootIdentity(): BootIdentity
}
