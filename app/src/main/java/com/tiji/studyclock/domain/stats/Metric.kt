package com.tiji.studyclock.domain.stats

enum class ConfidenceLevel { TRUSTED, REFERENCE, INSUFFICIENT }

/** A non-null value can still be a small-sample observation, not an ability estimate. */
data class Metric<T>(
    val value: T?,
    val level: ConfidenceLevel,
    val questionCount: Int,
    val sessionCount: Int,
    val unit: String,
    val unavailableReason: String? = null,
)

/**
 * 完整登记表与设计书 §4.3 一一对应：原 26 项 + v5 的 G/R/X 15 项 + v0.11 的 P1–P5 = 46 项。
 * [implementedInMvp] 是原有 13 项数值引擎；[implementedInV5] 是 v5 实装的 13 项；
 * [implementedInV11] 是专注模式的 P1–P5。
 * G6（目标分差距）与 R6（滞留袋占比）只登记、不输出数值，避免「以登记代实装」。
 */
enum class MetricId(
    val implementedInMvp: Boolean = false,
    val implementedInV5: Boolean = false,
    val implementedInV11: Boolean = false,
) {
    A1(true), A2(true), A3(true), A4(true),
    B1(true), B2(true), B3(true), B4(true), B5(true), B6(true),
    C1, C2, C3, C4, C5, C6,
    D1(true), D2(true), D3, D4, D5(true),
    E1, E2, F1, F2, F3,
    G1(false, true), G2(false, true), G3(false, true), G4(false, true), G5(false, true), G6,
    R1(false, true), R2(false, true), R3(false, true), R4(false, true), R5(false, true), R6,
    X1(false, true), X2(false, true), X3(false, true),
    P1(false, false, true), P2(false, false, true), P3(false, false, true),
    P4(false, false, true), P5(false, false, true),
    ;

    val isImplemented: Boolean get() = implementedInMvp || implementedInV5 || implementedInV11
}

/** Product defaults from design §4, not scientific or causal thresholds. */
object Thresholds {
    const val WINDOW_DAYS = 30L
    const val TRUSTED_QUESTIONS = 30
    const val TRUSTED_SESSIONS = 3
    const val REFERENCE_QUESTIONS = 15
    const val REFERENCE_SESSIONS = 2
    const val SERIES_MIN_SESSIONS = 3
    const val SPEED_BASELINE_MIN_CATEGORIES = 3
    const val PRESET_CATEGORY_COUNT = 43
    // Matches CategorySeed v1 IDs without depending on the data layer or mutable catalog size.
    val PRESET_CATEGORY_IDS: Set<Long> = (1L..43L).toSet()
    const val WILSON_Z = 1.96
    const val TREND_CHANGE = 0.02
    const val LOW_ACCURACY = 0.70
    const val HIGH_ACCURACY = 0.85
    const val FAST_FACTOR = 0.80
    const val SLOW_FACTOR = 1.20
    const val LARGE_VOLATILITY = 0.10
    const val FRESH_DAYS = 7L
    // Used only when comparing floating-point results at a product threshold.
    private const val COMPARISON_EPSILON = 1e-12

    fun confidence(questionCount: Int, sessionCount: Int): ConfidenceLevel = when {
        questionCount >= TRUSTED_QUESTIONS && sessionCount >= TRUSTED_SESSIONS -> ConfidenceLevel.TRUSTED
        questionCount >= REFERENCE_QUESTIONS && sessionCount >= REFERENCE_SESSIONS -> ConfidenceLevel.REFERENCE
        else -> ConfidenceLevel.INSUFFICIENT
    }

    fun isRising(slope: Double): Boolean = slope > TREND_CHANGE + COMPARISON_EPSILON
    fun isDeclining(slope: Double): Boolean = slope < -TREND_CHANGE - COMPARISON_EPSILON
    fun isSteady(slope: Double): Boolean = !isRising(slope) && !isDeclining(slope)
    fun isLargeVolatility(value: Double): Boolean = value >= LARGE_VOLATILITY - COMPARISON_EPSILON
    fun isFast(seconds: Double, baseline: Double): Boolean = seconds < baseline * FAST_FACTOR - COMPARISON_EPSILON
    fun isSlow(seconds: Double, baseline: Double): Boolean = seconds > baseline * SLOW_FACTOR + COMPARISON_EPSILON
}
