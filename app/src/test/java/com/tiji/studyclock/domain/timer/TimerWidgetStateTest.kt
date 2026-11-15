package com.tiji.studyclock.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerWidgetStateTest {
    private val nowAt = 1_800_000_000_000L
    private val nowElapsed = 9_000_000L

    private fun start(
        targetSec: Int? = null,
        startElapsed: Long = nowElapsed,
        startAt: Long = nowAt,
        bootCount: Int = 7,
        paused: Boolean = false,
    ) = TimerStart(
        subjectId = 1,
        categoryId = 2,
        startAt = startAt,
        startElapsed = startElapsed,
        bootCount = bootCount,
        targetSec = targetSec,
        pauseStartedAt = if (paused) nowAt - 30_000 else null,
        pauseStartedElapsed = if (paused) nowElapsed - 30_000 else null,
    )

    @Test
    fun `idle offers a one tap start with the default duration`() {
        val state = TimerWidgetStates.build(null, null, null, null, nowAt, nowElapsed, 7)
        assertEquals(TimerWidgetState.Mode.IDLE, state.mode)
        assertEquals(WidgetAction.START, state.primary)
        assertEquals("开始 25 分钟", state.primaryLabel)
        assertEquals(WidgetAction.OPEN_CAPTURE, state.secondary)
        assertNull(state.chronometerBaseMillis)
    }

    @Test
    fun `countdown anchors a live countdown chronometer`() {
        val state = TimerWidgetStates.build(
            start(targetSec = 1500, startElapsed = nowElapsed - 600_000),
            null, "判断推理", "图形推理", nowAt, nowElapsed, 7,
        )
        assertEquals(TimerWidgetState.Mode.RUNNING, state.mode)
        assertEquals("图形推理", state.title)
        assertEquals("判断推理 · 目标 25:00", state.subtitle)
        assertTrue(state.countDown)
        assertEquals(nowElapsed + 900_000L, state.chronometerBaseMillis)
        assertEquals(WidgetAction.PAUSE, state.primary)
        assertEquals(WidgetAction.FINISH, state.secondary)
    }

    @Test
    fun `open ended timer counts up from its start`() {
        val state = TimerWidgetStates.build(
            start(targetSec = null, startElapsed = nowElapsed - 120_000),
            null, "判断推理", "图形推理", nowAt, nowElapsed, 7,
        )
        assertEquals(TimerWidgetState.Mode.RUNNING, state.mode)
        assertTrue(!state.countDown)
        assertEquals(nowElapsed - 120_000L, state.chronometerBaseMillis)
    }

    @Test
    fun `paused timer shows static net elapsed time`() {
        val state = TimerWidgetStates.build(
            start(targetSec = 1500, startElapsed = nowElapsed - 600_000, paused = true),
            null, "判断推理", "图形推理", nowAt, nowElapsed, 7,
        )
        assertEquals(TimerWidgetState.Mode.PAUSED, state.mode)
        assertNull(state.chronometerBaseMillis)
        assertEquals("09:30", state.staticTime)
        assertEquals(WidgetAction.RESUME, state.primary)
        assertEquals(WidgetAction.FINISH, state.secondary)
    }

    @Test
    fun `elapsed falls back to wall clock after a reboot`() {
        val state = TimerWidgetStates.build(
            start(targetSec = 1500, startAt = nowAt - 300_000, startElapsed = 1_000, bootCount = 6),
            null, "判断推理", "图形推理", nowAt, nowElapsed, 7,
        )
        assertEquals("判断推理 · 目标 25:00", state.subtitle)
        assertEquals(nowElapsed + 1_200_000L, state.chronometerBaseMillis)
    }

    @Test
    fun `finished timer points at the fill-in form`() {
        val finished = FinishedTimer(
            subjectId = 1, categoryId = 2, startAt = nowAt - 1_500_000,
            endAt = nowAt, durationSec = 1500, targetSec = 1500,
        )
        val state = TimerWidgetStates.build(null, finished, "判断推理", "图形推理", nowAt, nowElapsed, 7)
        assertEquals(TimerWidgetState.Mode.FINISHED, state.mode)
        assertEquals("计时已结束", state.title)
        assertEquals("判断推理 · 用时 25:00", state.subtitle)
        assertEquals("25:00", state.staticTime)
        assertEquals(WidgetAction.OPEN_TIMER, state.primary)
    }

    @Test
    fun `active timer wins over a stale finished one`() {
        val finished = FinishedTimer(
            subjectId = 1, categoryId = 2, startAt = nowAt - 1_500_000,
            endAt = nowAt, durationSec = 1500,
        )
        val state = TimerWidgetStates.build(
            start(targetSec = 1500), finished, "判断推理", "图形推理", nowAt, nowElapsed, 7,
        )
        assertEquals(TimerWidgetState.Mode.RUNNING, state.mode)
    }
}
