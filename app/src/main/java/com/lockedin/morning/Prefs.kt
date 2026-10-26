package com.lockedin.morning

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All settings and session state, stored in SharedPreferences (key = property name). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("lockedin", Context.MODE_PRIVATE)

    private fun str(def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            sp.getString(property.name, def) ?: def

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(property.name, value).commit()
        }
    }

    private fun int(def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
            sp.getInt(property.name, def)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(property.name, value).commit()
        }
    }

    private fun long(def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long =
            sp.getLong(property.name, def)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            sp.edit().putLong(property.name, value).commit()
        }
    }

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            sp.getBoolean(property.name, def)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(property.name, value).commit()
        }
    }

    // ---------- Settings ----------
    var enabled by bool(true)
    var claudeKey by str("")
    var claudeModel by str("claude-sonnet-5-5")
    var youtubeKey by str("")
    var channels by str("Alex Hormozi, Jeremy Miner")
    var extraVideoUrls by str("")
    var minMinutes by int(30)
    var maxMinutes by int(90)
    var windowStartHour by int(4)
    var windowEndHour by int(13)
    var alarmBufferMin by int(120)
    var quizQuestions by int(8)
    var quizSeconds by int(300)
    var passPercent by int(70)
    var messagingApps by str(
        "com.facebook.orca,com.google.android.apps.messaging,com.samsung.android.messaging,com.android.mms"
    )
    var messagingBudgetSec by int(300)
    var messagingVisitSec by int(120)

    // ---------- Session state ----------
    var completedDay by str("")
    var active by bool(false)
    var sessionDay by str("")
    var phase by str("")
    var sessionStartedAt by long(0L)
    var pickError by str("")
    var playerErrors by int(0)

    var videoId by str("")
    var videoTitle by str("")
    var videoAuthor by str("")
    var videoDuration by int(0)
    var positionSec by int(0)
    var watchedSec by int(0)

    var extUses by int(0)
    var extMinutesUsed by int(0)
    var extUntil by long(0L)

    var msgUsedSec by int(0)

    var quizJson by str("")
    var quizAnswers by str("")
    var quizStartedAt by long(0L)
    var quizRound by int(0)
    var quizFails by int(0)
    var quizPrevQs by str("")

    // ---------- Video pool ----------
    var poolJson by str("")
    var poolFetchedAt by long(0L)
    var watchedIds by str("")
    var historyJson by str("[]")
}
