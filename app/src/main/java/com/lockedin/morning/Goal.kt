package com.lockedin.morning

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Weekly learning goal: daily minutes × 7 (weeks run Monday–Sunday).
 * All video watching counts. If you're short on Sunday, the lock makes you finish.
 */
object Goal {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun enabled(p: Prefs) = p.dailyGoalMin > 0
    fun weekTargetSec(p: Prefs) = p.dailyGoalMin * 60 * 7

    /** Monday = 1 … Sunday = 7 */
    fun dayOfWeek(): Int {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    fun isLastDay() = dayOfWeek() == 7

    /** The 7 dates of this week, Monday first. */
    fun weekDates(): List<String> {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, -(dayOfWeek() - 1))
        return (0 until 7).map { fmt.format(c.time).also { _ -> c.add(Calendar.DAY_OF_MONTH, 1) } }
    }

    /** Seconds watched on each day of this week (Mon..Sun). */
    fun perDay(ctx: Context): List<Double> = weekDates().map { Stats.watchedOn(ctx, it) }

    fun doneSec(ctx: Context): Double = perDay(ctx).sum()

    /** Short of where you should be by the end of yesterday? */
    fun behindSec(ctx: Context, p: Prefs): Double =
        (p.dailyGoalMin * 60.0 * (dayOfWeek() - 1) - doneSec(ctx)).coerceAtLeast(0.0)

    /** What a catch-up would cover: back on track including today. */
    fun catchUpSec(ctx: Context, p: Prefs): Double =
        (p.dailyGoalMin * 60.0 * dayOfWeek() - doneSec(ctx)).coerceAtLeast(0.0)

    fun remainingWeekSec(ctx: Context, p: Prefs): Double =
        (weekTargetSec(p) - doneSec(ctx)).coerceAtLeast(0.0)

    fun hm(sec: Double): String {
        val m = (sec / 60).toInt()
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}
