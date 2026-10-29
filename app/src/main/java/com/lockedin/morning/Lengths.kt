package com.lockedin.morning

/** The video-length choices (set once in Settings / setup; used every morning). */
object Lengths {
    data class Len(val label: String, val min: Int, val max: Int)

    fun options(p: Prefs): List<Len> {
        val floor = p.minMinutes.coerceAtLeast(1)
        val raw = listOf(Len("30–45 min", 30, 45), Len("45–60 min", 45, 60), Len("1–1.5 hours", 60, 90), Len("1.5 hours +", 90, 240))
        val list = raw.filter { it.max > floor }.map { if (it.min < floor) it.copy(label = "$floor–${it.max} min", min = floor) else it }
        return list + Len("Any length", floor, 240)
    }

    fun current(p: Prefs): Len {
        val opts = options(p)
        return opts.firstOrNull { it.min * 60 == p.pickMinSec && it.max * 60 == p.pickMaxSec } ?: opts.first()
    }

    fun set(p: Prefs, l: Len) {
        p.pickMinSec = l.min * 60
        p.pickMaxSec = l.max * 60
    }
}
