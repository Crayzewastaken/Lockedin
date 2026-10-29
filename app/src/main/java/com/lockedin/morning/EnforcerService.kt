package com.lockedin.morning

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * The always-running part of the app (an Accessibility Service, so Android keeps it alive):
 *  - notices your first unlock of the day and starts the session (unless an alarm is due soon)
 *  - while a session is active, sends you back to the video if you open any other app
 *  - lets calls, clock/alarms and (briefly) messaging apps through
 */
class EnforcerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EnforcerService? = null

        private const val CHANNEL = "session"
        private const val EXT_NOTIF_ID = 7

        /** Always allowed: phone calls, emergency, alarms. */
        private val ALWAYS_ALLOWED = setOf(
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.google.android.deskclock",
            "com.android.deskclock", "com.sec.android.app.clockpackage",
        )

        /** Places you could switch the app off from. Blocked for the whole session, breaks included. */
        private val TAMPER = setOf(
            "com.android.settings", "com.samsung.android.settings", "com.google.android.packageinstaller",
            "com.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.samsung.android.lool", "com.android.vending",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg = ""
    private var msgPkg = ""
    private var msgEnterAt = 0L
    private var lastBounce = 0L
    private var extNotified = 0L
    private var imePkgs: Set<String> = emptySet()
    private var registered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            handler.postDelayed({ onUnlock() }, 1500)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                periodic()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        registered = true
        createChannel()
        refreshIme()
        handler.post(tick)
        // The service may come up after you've already unlocked (e.g. after a reboot).
        handler.postDelayed({ onUnlock() }, 3000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnorable(pkg)) return
        currentPkg = pkg
        try {
            if (enforceRecall(Prefs(this))) return
            enforce()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun locked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        return km != null && km.isKeyguardLocked
    }

    private fun onUnlock() {
        if (locked()) return
        refreshIme()
        val p = Prefs(this)
        if (!p.active && Session.shouldStart(this)) {
            Session.start(this)
        } else if (p.active) {
            enforce()
        }
    }

    private var lastScrollTick = 0L
    private var lastRecallBounce = 0L

    /** While a scroll recall quiz is running you can't leave it. Returns true if it's running. */
    private fun enforceRecall(p: Prefs): Boolean {
        if (!p.recallActive) return false
        if (System.currentTimeMillis() > p.recallUntil + 120_000L) { // safety: never stuck
            p.recallActive = false
            Scroll.promptShowing = false
            return false
        }
        if (locked()) return true
        val pkg = currentPkg
        if (pkg.isEmpty() || pkg == packageName || isAlwaysAllowed(pkg)) return true
        val t = SystemClock.elapsedRealtime()
        if (t - lastRecallBounce < 800) return true
        lastRecallBounce = t
        try {
            startActivity(
                Intent(this, CheckInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CheckInActivity.EXTRA_APP, p.recallApp)
            )
        } catch (_: Exception) {
        }
        return true
    }

    /** Counts time in Instagram/TikTok and triggers the check-in every 15–20 minutes. */
    private fun scrollTick(p: Prefs, pkg: String?) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastScrollTick == 0L) 0.0 else ((now - lastScrollTick) / 1000.0).coerceAtMost(5.0)
        lastScrollTick = now
        if (!p.scrollEnabled || p.active || Scroll.promptShowing) return
        if (pkg == null) return
        val apps = Scroll.apps(p)
        if (pkg in apps) {
            // Back from a long gap (5+ min away): start a fresh count.
            if (Scroll.awaySince != 0L && now - Scroll.awaySince > 5 * 60_000L) Scroll.sinceCheckInSec = 0.0
            Scroll.awaySince = 0L
            Scroll.ensureInterval(p)
            Scroll.lastSeenApp = pkg
            Scroll.lastSeenAt = System.currentTimeMillis()
            Scroll.currentApp = pkg
            Scroll.sinceCheckInSec += dt
            Scroll.addTime(this, pkg, dt)
            if (Scroll.sinceCheckInSec >= Scroll.nextPromptSec) {
                Scroll.promptShowing = true
                val i = Intent(this, CheckInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CheckInActivity.EXTRA_APP, pkg)
                    .putExtra(CheckInActivity.EXTRA_MIN, (Scroll.sinceCheckInSec / 60).toInt())
                try { startActivity(i) } catch (_: Exception) { Scroll.promptShowing = false }
            }
        } else if (pkg != packageName && Scroll.awaySince == 0L) {
            Scroll.awaySince = now
        }
    }

    private fun periodic() {
        val p = Prefs(this)
        run {
            val pm = getSystemService(PowerManager::class.java)
            val on = (pm == null || pm.isInteractive) && !locked()
            if (on) {
                val root = try { rootInActiveWindow } catch (_: Exception) { null }
                val pkg = root?.packageName?.toString()
                if (pkg != null && !isIgnorable(pkg)) currentPkg = pkg
                if (enforceRecall(p)) return
                scrollTick(p, if (currentPkg.isEmpty()) null else currentPkg)
            } else {
                lastScrollTick = 0L
                if (Scroll.awaySince == 0L) Scroll.awaySince = SystemClock.elapsedRealtime()
                Scroll.flush(this)
            }
        }
        if (!p.active) {
            if (extNotified != 0L) clearExtNotification()
            return
        }
        if (Session.isStale(p)) {
            Session.finish(this, "Missed (session never finished)")
            return
        }
        if (Session.inExtension(p)) {
            if (extNotified != p.extUntil) showExtNotification(p.extUntil)
        } else if (extNotified != 0L) {
            clearExtNotification()
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        if (locked()) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val pkg = root?.packageName?.toString()
        if (pkg != null && !isIgnorable(pkg)) currentPkg = pkg
        enforce()
    }

    private fun enforce() {
        val p = Prefs(this)
        if (!p.active || locked()) return
        if (Session.inWorkHours(p)) {
            // Work hours: hands off. The session carries on after work.
            LockActivity.current?.leaveForWork()
            return
        }
        val pkg = currentPkg
        val now = System.currentTimeMillis()

        // Close out a messaging visit once you've left that app.
        if (msgPkg.isNotEmpty() && pkg != msgPkg) {
            p.msgUsedSec = p.msgUsedSec + ((now - msgEnterAt) / 1000).toInt()
            msgPkg = ""
        }

        if (pkg.isEmpty() || pkg == packageName) return

        if (isTamper(pkg)) {
            // While a video still can't be found (e.g. no internet) you may need Wi-Fi settings.
            val offline = p.phase == Session.PICKING && p.pickError.isNotEmpty()
            if (!offline) {
                bounce("Settings are locked until today's session is done")
                return
            }
        }

        if (Session.inExtension(p)) return
        if (isAlwaysAllowed(pkg)) return
        if (isMessaging(p, pkg)) {
            handleMessaging(p, pkg, now)
            return
        }
        bounce(null)
    }

    private fun handleMessaging(p: Prefs, pkg: String, now: Long) {
        if (msgPkg != pkg) {
            if (NotificationWatcher.isEnabled(this) &&
                now - NotificationWatcher.lastPosted(this, pkg) > 10 * 60_000L
            ) {
                bounce("Messages can only be opened after a new one arrives")
                return
            }
            if (p.msgUsedSec >= p.messagingBudgetSec) {
                bounce("You've used today's ${p.messagingBudgetSec / 60} min of message time")
                return
            }
            msgPkg = pkg
            msgEnterAt = now
            return
        }
        val visit = ((now - msgEnterAt) / 1000).toInt()
        if (visit >= p.messagingVisitSec || p.msgUsedSec + visit >= p.messagingBudgetSec) {
            p.msgUsedSec = p.msgUsedSec + visit
            msgPkg = ""
            bounce("Reply time's up. Back to the video.")
        }
    }

    private fun bounce(msg: String?) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastBounce < 800) return
        lastBounce = t
        Session.launch(this, msg)
    }

    private fun isIgnorable(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg == "android" || pkg in imePkgs ||
            pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") || pkg.contains(".ime")

    private fun isAlwaysAllowed(pkg: String): Boolean =
        pkg in ALWAYS_ALLOWED || pkg.contains("dialer") || pkg.contains("incallui") ||
            pkg.contains("telecom") || pkg.contains("deskclock") || pkg.contains("clockpackage")

    private fun isTamper(pkg: String): Boolean =
        pkg in TAMPER || pkg.contains("packageinstaller") || pkg.endsWith(".settings")

    private fun isMessaging(p: Prefs, pkg: String): Boolean =
        p.messagingApps.split(",").map { it.trim() }.any { it.isNotEmpty() && it == pkg }

    private fun refreshIme() {
        imePkgs = try {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
                ?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // ------------------------------------------------------------------ break countdown notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Morning session", NotificationManager.IMPORTANCE_LOW))
    }

    private fun showExtNotification(until: Long) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Break — the video comes back when this hits zero")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(until)
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(EXT_NOTIF_ID, n)
        } catch (_: Exception) {
        }
        extNotified = until
    }

    fun clearExtNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(EXT_NOTIF_ID)
        extNotified = 0L
    }
}
