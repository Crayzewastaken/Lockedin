package com.lockedin.morning

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.ceil

/** The full-screen "lock": picks the video, plays it, runs the quiz. */
class LockActivity : Activity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        private const val RED = 0xFFD64545.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
        private const val MUTED = 0xFFA0A0A8.toInt()
        private const val PLAYER_HOST = "appassets.androidplatform.net"
        private const val EMERGENCY_PHRASE = "I am choosing to skip my morning session today and I accept that"
    }

    private lateinit var p: Prefs
    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private var screen = ""
    private var web: WebView? = null
    private var userPaused = false
    private var lastTickWall = 0L
    private var lastCur = 0.0
    private var maxReached = 0.0
    private var portraitMode = false
    private var controlsShown = true
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var quizWaitStarted = 0L
    private var endedHandled = false

    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var playBtn: Button? = null
    private var extBtn: Button? = null

    private var picking = false
    private var quizGenRunning = false
    private var quizStatus: TextView? = null
    private var bgRetries = 0
    private var answering = false
    private var ticker: Runnable? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        hideBars()
        render()
        showMsg(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (screenKey() != screen) render()
        showMsg(intent)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        if (screenKey() != screen) {
            render()
        } else if (screen == Session.VIDEO && !userPaused) {
            js("doPlay()")
        }
    }

    override fun onPause() {
        super.onPause()
        if (screen == Session.VIDEO) js("doPause()")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Deprecated("Back is disabled on the lock screen")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!p.active) super.onBackPressed()
        else toast("Finish the video and quiz first.")
    }

    override fun onDestroy() {
        stopTicker()
        destroyWeb()
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun screenKey(): String = when {
        !p.active -> "none"
        Session.inExtension(p) -> "ext"
        p.phase.isEmpty() -> Session.PICKING
        else -> p.phase
    }

    private fun render() {
        val key = screenKey()
        when (key) {
            "none" -> { if (screen != "done") finish(); return }
            "ext" -> showExtension()
            Session.VIDEO -> showVideo()
            Session.QUIZ -> showQuiz(null)
            else -> showPicking()
        }
    }

    // ------------------------------------------------------------------ picking

    private fun showPicking() {
        clear(Session.PICKING)
        val col = column()
        col.addView(label("Good morning.", 30f, Color.WHITE, bold = true))
        col.addView(label("Finding today's video from ${p.channels}…", 16f, MUTED))
        col.addView(ProgressBar(this).apply { isIndeterminate = true }, lp(top = 24))
        val status = label(if (p.pickError.isNotEmpty()) "Last try: ${p.pickError}" else "", 14f, MUTED)
        col.addView(status, lp(top = 16))
        val stuckFor = System.currentTimeMillis() - p.sessionStartedAt
        val brokenPlayer = p.playerErrors >= 4
        if ((p.pickError.isNotEmpty() && stuckFor > 20 * 60_000L) || brokenPlayer) {
            val why = if (brokenPlayer) "videos won't play" else "no internet"
            col.addView(button("Skip today ($why)", CARD) {
                Session.finish(this, "Skipped ($why)", p.pickError)
                showDone("Skipped today because no video could load. This is recorded in your history.")
            }, lp(top = 24))
        }
        val emergency = label("Emergency exit", 12f, 0xFF66666C.toInt())
        emergency.gravity = Gravity.CENTER
        emergency.setOnClickListener { showEmergencyDialog() }
        col.addView(emergency, lp(top = 32))
        root.addView(scroll(col))

        if (picking) return
        picking = true
        io.execute {
            var err: String? = null
            try {
                val v = YouTube.pick(this, p)
                Session.applyPick(this, v)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                p.pickError = err ?: "error"
            }
            ui.post {
                picking = false
                if (isFinishing || isDestroyed) return@post
                if (err == null) {
                    render()
                } else {
                    status.text = "Couldn't load a video: $err\nTrying again in 30 seconds…"
                    ui.postDelayed({
                        if (!isFinishing && screenKey() == Session.PICKING) showPicking()
                    }, 30_000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ video

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showVideo() {
        clear(Session.VIDEO)
        // Full screen, landscape (turn the phone either way). The rotate button switches to portrait.
        requestedOrientation = if (portraitMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        maxReached = p.watchedSec.toDouble()
        lastCur = p.positionSec.toDouble()
        lastTickWall = 0L
        endedHandled = false
        userPaused = false // the player autoplays on load, so the button must start as "Pause"

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)

        val wv = WebView(this)
        web = wv
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = WebChromeClient()
        val startAt = (minOf(p.positionSec, p.watchedSec) - 3).coerceAtLeast(0)
        val html = playerHtml(p.videoId, startAt)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request?.url?.host == PLAYER_HOST) {
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Never let the page navigate away from the player.
                if (request == null) return false
                return request.isForMainFrame && request.url?.host != PLAYER_HOST
            }
        }
        wv.addJavascriptInterface(Bridge(), "Android")
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Invisible layer over the video: blocks YouTube's own controls, tap = show/hide our controls.
        val tapLayer = View(this)
        tapLayer.setOnClickListener { if (controlsShown) hideControls() else showControls() }
        frame.addView(tapLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- top bar: title · emergency exit
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        val title = label("${p.videoTitle}  ·  ${p.videoAuthor}", 14f, Color.WHITE, bold = true)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val emergency = label("Emergency exit", 12f, 0xFF8A8A90.toInt())
        emergency.setPadding(dp(12), dp(6), dp(4), dp(6))
        emergency.setOnClickListener { showEmergencyDialog() }
        val qs = label("", 12f, 0xFF8A8A90.toInt())
        qs.setPadding(dp(12), dp(6), dp(4), dp(6))
        quizStatus = qs
        top.addView(qs)
        top.addView(emergency)
        frame.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---- bottom bar: progress, then  ⟲30 ⟲10 ▶/❚❚ · time · Break ⟳
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(24), dp(14), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
        }
        val bar2 = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        progressBar = bar2
        bottom.addView(bar2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val small = { t: String, f: () -> Unit -> button(t, 0x55FFFFFF, f).apply { textSize = 14f } }
        row.addView(small("⟲ 30s") { rewind(30) }, LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginEnd = dp(6) })
        row.addView(small("⟲ 10s") { rewind(10) }, LinearLayout.LayoutParams(dp(68), dp(44)).apply { marginEnd = dp(6) })
        val pb = button("❚❚  Pause", ORANGE) { togglePlay() }
        playBtn = pb
        row.addView(pb, LinearLayout.LayoutParams(dp(108), dp(44)))
        val pt = label("", 13f, 0xFFDDDDDD.toInt())
        pt.setPadding(dp(12), 0, dp(8), 0)
        pt.maxLines = 2
        progressText = pt
        row.addView(pt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val eb = button("Break", 0x55FFFFFF) { showBreakDialog() }
        eb.textSize = 13f
        extBtn = eb
        row.addView(eb, LinearLayout.LayoutParams(dp(96), dp(44)).apply { marginEnd = dp(6) })
        row.addView(small("⟳") {
            portraitMode = !portraitMode
            requestedOrientation = if (portraitMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            showControls()
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        bottom.addView(row)
        frame.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        topBar = top
        bottomBar = bottom
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateProgressUi()
        showControls()
        wv.loadUrl("https://$PLAYER_HOST/player.html")

        // Start writing the quiz in the background while you watch.
        bgRetries = 0
        if (p.quizJson.isEmpty() && Quiz.canQuiz(this, p)) ensureQuiz(background = true)
        updateQuizStatus(null)
    }

    /** Small note in the top bar so you can see the quiz being written while you watch. */
    private fun updateQuizStatus(err: String?) {
        val v = quizStatus ?: return
        v.text = when {
            p.quizJson.isNotEmpty() -> "Quiz ready ✓"
            !Quiz.canQuiz(this, p) -> "No transcript: summary at end"
            quizGenRunning -> "Writing quiz…"
            err != null -> "Quiz failed, retrying…"
            else -> ""
        }
        if (err != null) v.setOnClickListener { toast(err) } else v.setOnClickListener(null)
    }

    private val hideRunnable = Runnable { hideControls() }

    private fun showControls() {
        controlsShown = true
        topBar?.animate()?.alpha(1f)?.setDuration(150)?.start()
        bottomBar?.animate()?.alpha(1f)?.setDuration(150)?.start()
        topBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        ui.removeCallbacks(hideRunnable)
        if (!userPaused) ui.postDelayed(hideRunnable, 4000)
    }

    private fun hideControls() {
        if (userPaused) return
        controlsShown = false
        topBar?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
    }

    private fun rewind(sec: Int) {
        js("back($sec)")
        showControls()
    }

    private fun playerHtml(videoId: String, start: Int): String = """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden}
#p{position:absolute;top:0;left:0;width:100%;height:100%}
#shield{position:absolute;top:0;left:0;right:0;bottom:0;z-index:10}
</style></head><body>
<div id="p"></div><div id="shield"></div>
<script>
var player=null, ready=false;
var tag=document.createElement('script'); tag.src='https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$videoId',
    playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,playsinline:1,
      modestbranding:1,start:$start,origin:'https://$PLAYER_HOST'},
    events:{
      onReady:function(e){ready=true;try{e.target.setPlaybackRate(1);}catch(x){} e.target.playVideo();},
      onStateChange:function(e){Android.onState(e.data);},
      onError:function(e){Android.onError(e.data);}
    }});
}
setInterval(function(){
  if(!ready||!player||!player.getCurrentTime) return;
  try{
    if(player.getPlaybackRate && player.getPlaybackRate()!=1) player.setPlaybackRate(1);
    Android.onTick(player.getCurrentTime(), player.getDuration(), player.getPlayerState());
  }catch(x){}
},1000);
function doPlay(){ if(ready&&player) player.playVideo(); }
function doPause(){ if(ready&&player) player.pauseVideo(); }
function back(s){ if(ready&&player){ player.seekTo(Math.max(0, player.getCurrentTime()-s), true); } }
function seekTo(t){ if(ready&&player){ player.seekTo(Math.max(0,t), true); player.playVideo(); } }
</script></body></html>
""".trimIndent()

    inner class Bridge {
        @JavascriptInterface
        fun onTick(cur: Double, dur: Double, state: Int) {
            ui.post { handleTick(cur, dur, state) }
        }

        @JavascriptInterface
        fun onState(state: Int) {
            ui.post { if (state == 0) handleEnded() }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            ui.post { handleError(code) }
        }
    }

    /**
     * "Watched" = the furthest point you've reached by actually playing the video.
     * Rewinding is free; playing back over old ground doesn't add anything, and the frontier
     * can only move forward at normal playing speed, so there's no way to skip ahead.
     */
    private fun handleTick(cur: Double, dur: Double, state: Int) {
        if (screen != Session.VIDEO) return
        val now = SystemClock.elapsedRealtime()
        if (state == 1) { // playing
            val allowance = if (lastTickWall > 0) (now - lastTickWall) / 1000.0 * 1.25 + 1.0 else 3.0
            if (cur > maxReached && cur <= maxReached + allowance) maxReached = cur
            lastTickWall = now
        } else {
            lastTickWall = 0L
        }
        lastCur = cur
        if (dur > 0 && dur.toInt() != p.videoDuration) p.videoDuration = dur.toInt()
        p.positionSec = cur.toInt()
        p.watchedSec = maxReached.toInt()
        updateProgressUi()
    }

    private fun updateProgressUi() {
        val d = p.videoDuration.coerceAtLeast(1)
        progressBar?.progress = ((maxReached / d) * 1000).toInt().coerceIn(0, 1000)
        val left = (d - maxReached.toInt()).coerceAtLeast(0)
        val behind = (maxReached - lastCur).toInt()
        progressText?.text = if (behind > 5) {
            "Rewound ${YouTube.clock(behind)} · catching up to ${YouTube.clock(maxReached.toInt())}"
        } else {
            "${YouTube.clock(maxReached.toInt())} / ${YouTube.clock(d)} · ${YouTube.clock(left)} left"
        }
        val m = Session.extMinutesLeft(p)
        val u = Session.extUsesLeft(p)
        extBtn?.text = if (m >= 5 && u > 0) "Break ($m)" else "No breaks"
    }

    private fun handleEnded() {
        if (screen != Session.VIDEO || endedHandled) return
        val d = p.videoDuration.coerceAtLeast(1)
        if (maxReached >= d * 0.95) {
            endedHandled = true
            p.phase = Session.QUIZ
            p.quizStartedAt = 0L
            p.quizAnswers = ""
            showQuiz(null)
        } else {
            toast("You skipped ahead somehow. Going back to ${YouTube.clock(maxReached.toInt())}.")
            js("seekTo(${(maxReached - 3).toInt()})")
        }
    }

    private fun handleError(code: Int) {
        if (screen != Session.VIDEO) return
        toast("This video can't play here (error $code). Picking another…")
        Session.markWatched(p, p.videoId) // so it isn't picked again
        p.playerErrors = p.playerErrors + 1
        p.videoId = ""
        p.phase = Session.PICKING
        Session.resetQuiz(p)
        showPicking()
    }

    private fun togglePlay() {
        userPaused = !userPaused
        if (userPaused) js("doPause()") else js("doPlay()")
        playBtn?.text = if (userPaused) "▶  Play" else "❚❚  Pause"
        showControls()
    }

    private fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    // ------------------------------------------------------------------ breaks (extensions)

    private fun showBreakDialog() {
        val left = Session.extMinutesLeft(p)
        val uses = Session.extUsesLeft(p)
        if (uses <= 0 || left < 5) {
            toast("No breaks left today.")
            return
        }
        val opts = listOf(5, 10, 15).filter { it <= left }
        AlertDialog.Builder(this)
            .setTitle("Take a break? ($left min left today, $uses ${if (uses == 1) "break" else "breaks"})")
            .setItems(opts.map { "$it minutes" }.toTypedArray()) { _, which -> startBreak(opts[which]) }
            .setNegativeButton("Keep watching", null)
            .show()
    }

    private fun startBreak(minutes: Int) {
        if (!Session.startExtension(p, minutes)) return
        js("doPause()")
        toast("Break started: $minutes min. The video comes back automatically.")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        showExtension()
    }

    private fun showExtension() {
        clear("ext")
        val col = column()
        col.addView(label("On a break", 28f, Color.WHITE, bold = true))
        val count = label("", 44f, ORANGE, bold = true)
        col.addView(count, lp(top = 12))
        col.addView(label("Your video is paused at ${YouTube.clock(p.positionSec)}. It comes back on its own when the timer ends.", 15f, MUTED), lp(top = 8))
        col.addView(button("End break and watch now", ORANGE) {
            p.extUntil = 0L
            EnforcerService.instance?.clearExtNotification()
            render()
        }, lp(top = 24))
        root.addView(scroll(col))
        val r = object : Runnable {
            override fun run() {
                val ms = p.extUntil - System.currentTimeMillis()
                if (ms <= 0) {
                    render()
                    return
                }
                count.text = YouTube.clock((ms / 1000).toInt() + 1)
                ui.postDelayed(this, 500)
            }
        }
        ticker = r
        ui.post(r)
    }

    // ------------------------------------------------------------------ quiz

    private var quizGenToken = 0

    private fun ensureQuiz(background: Boolean = false) {
        if (quizGenRunning || p.quizJson.isNotEmpty()) return
        quizGenRunning = true
        val token = ++quizGenToken
        io.execute {
            var err: String? = null
            var json: String? = null
            try {
                json = Quiz.toJson(Quiz.generate(this, p))
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            ui.post {
                if (token != quizGenToken) return@post // "Start over" was pressed; ignore this old attempt
                quizGenRunning = false
                if (json != null) {
                    p.quizJson = json
                } else if (!background) {
                    p.quizFails = p.quizFails + 1
                }
                if (isFinishing || isDestroyed) return@post
                when (screen) {
                    Session.VIDEO -> {
                        updateQuizStatus(err)
                        // Failed while you watch: quietly try again in a couple of minutes.
                        if (json == null && bgRetries < 5) {
                            bgRetries++
                            ui.postDelayed({
                                if (screen == Session.VIDEO && p.quizJson.isEmpty()) {
                                    ensureQuiz(background = true)
                                    updateQuizStatus(null)
                                }
                            }, 120_000L)
                        }
                    }
                    Session.QUIZ -> if (p.quizStartedAt == 0L) showQuiz(err)
                }
            }
        }
    }

    private fun showQuiz(err: String?) {
        clear(Session.QUIZ)
        if (!Quiz.canQuiz(this, p) || (p.quizJson.isEmpty() && p.quizFails >= 4)) {
            showReflection()
            return
        }
        if (p.quizJson.isEmpty()) {
            val col = column()
            col.addView(label("Nice. Video done.", 28f, Color.WHITE, bold = true))
            val escape = button("Skip the quiz → write a summary instead", CARD) { showReflection() }
            if (err == null) {
                if (quizWaitStarted == 0L) quizWaitStarted = SystemClock.elapsedRealtime()
                col.addView(label("Writing your quiz…", 16f, MUTED), lp(top = 8))
                col.addView(ProgressBar(this).apply { isIndeterminate = true }, lp(top = 16))
                val waited = label("", 13f, MUTED)
                col.addView(waited, lp(top = 8))
                val retry = button("Start over", ORANGE) {
                    quizWaitStarted = 0L
                    quizGenToken++
                    quizGenRunning = false
                    showQuiz(null)
                }
                retry.visibility = View.GONE
                escape.visibility = View.GONE
                col.addView(retry, lp(top = 20))
                col.addView(escape, lp(top = 10))
                ensureQuiz()
                val r = object : Runnable {
                    override fun run() {
                        val sec = ((SystemClock.elapsedRealtime() - quizWaitStarted) / 1000).toInt()
                        waited.text = "${sec}s"
                        if (sec >= 25) { retry.visibility = View.VISIBLE; escape.visibility = View.VISIBLE }
                        ui.postDelayed(this, 1000)
                    }
                }
                ticker = r
                ui.post(r)
            } else {
                quizWaitStarted = 0L
                col.addView(label("Couldn't write the quiz:", 15f, RED, bold = true), lp(top = 8))
                col.addView(label(err, 14f, RED).apply { setTextIsSelectable(true) }, lp(top = 4))
                col.addView(button("Try again", ORANGE) { showQuiz(null) }, lp(top = 16))
                col.addView(escape, lp(top = 10))
            }
            root.addView(scroll(col))
            return
        }

        val qs = Quiz.fromJson(p.quizJson)
        if (qs.isEmpty()) {
            Session.resetQuiz(p)
            showQuiz(null)
            return
        }
        if (p.quizStartedAt == 0L) p.quizStartedAt = System.currentTimeMillis()
        val answers = answers()
        val deadline = p.quizStartedAt + p.quizSeconds * 1000L
        if (answers.size >= qs.size || System.currentTimeMillis() >= deadline) {
            showQuizResult(qs, answers)
            return
        }

        val i = answers.size
        val q = qs[i]
        val col = column(center = false)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label("Question ${i + 1} of ${qs.size}", 14f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val timer = label("", 16f, ORANGE, bold = true)
        top.addView(timer)
        col.addView(top)
        col.addView(label(q.q, 21f, Color.WHITE, bold = true), lp(top = 16))
        val buttons = mutableListOf<Button>()
        q.options.forEachIndexed { idx, opt ->
            val b = button(opt, CARD) { answer(q, idx, buttons) }
            b.isAllCaps = false
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setPadding(dp(16), dp(14), dp(16), dp(14))
            buttons.add(b)
            col.addView(b, lp(top = 10))
        }
        root.addView(scroll(col))

        val r = object : Runnable {
            override fun run() {
                val ms = deadline - System.currentTimeMillis()
                if (ms <= 0) {
                    showQuizResult(qs, answers())
                    return
                }
                timer.text = YouTube.clock((ms / 1000).toInt() + 1)
                ui.postDelayed(this, 500)
            }
        }
        ticker = r
        ui.post(r)
    }

    private fun answers(): List<Int> =
        p.quizAnswers.split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun answer(q: Quiz.Q, idx: Int, buttons: List<Button>) {
        if (answering) return
        answering = true
        val list = answers().toMutableList()
        list.add(idx)
        p.quizAnswers = list.joinToString(",")
        buttons.getOrNull(q.answer)?.background = rounded(GREEN)
        if (idx != q.answer) buttons.getOrNull(idx)?.background = rounded(RED)
        ui.postDelayed({
            answering = false
            if (screen == Session.QUIZ) showQuiz(null)
        }, 700)
    }

    private fun showQuizResult(qs: List<Quiz.Q>, answers: List<Int>) {
        clear(Session.QUIZ)
        val n = qs.size
        val score = qs.indices.count { answers.getOrNull(it) == qs[it].answer }
        val needed = ceil(p.passPercent * n / 100.0).toInt()
        val col = column(center = false)

        if (score >= needed) {
            Session.finish(this, "Passed quiz $score/$n", p.videoTitle)
            col.addView(label("Locked in. ✅", 30f, Color.WHITE, bold = true))
            col.addView(label("You scored $score/$n. Your phone is yours.", 17f, MUTED), lp(top = 8))
            col.addView(label("Today's lessons", 18f, ORANGE, bold = true), lp(top = 24))
            for (q in qs) if (q.explain.isNotBlank()) col.addView(label("•  ${q.explain}", 15f, Color.WHITE), lp(top = 8))
            col.addView(button("Start your day", ORANGE) { finish() }, lp(top = 28))
            screen = "done"
        } else {
            col.addView(label("Not yet: $score/$n", 30f, Color.WHITE, bold = true))
            col.addView(label("You need $needed/$n to pass. Here's what you missed:", 16f, MUTED), lp(top = 8))
            for (i in qs.indices) {
                if (answers.getOrNull(i) == qs[i].answer) continue
                val q = qs[i]
                val stamp = if (q.at.isNotBlank()) " (at ${q.at})" else ""
                col.addView(label(q.q, 15f, Color.WHITE, bold = true), lp(top = 16))
                col.addView(label("Answer: ${q.options[q.answer]}$stamp", 14f, GREEN), lp(top = 4))
                if (q.explain.isNotBlank()) col.addView(label(q.explain, 14f, MUTED), lp(top = 2))
            }
            col.addView(button("Take a new quiz", ORANGE) {
                p.quizPrevQs = (p.quizPrevQs + "\n" + qs.joinToString("\n") { "- " + it.q }).takeLast(8000)
                p.quizRound = p.quizRound + 1
                Session.resetQuiz(p)
                showQuiz(null)
            }, lp(top = 28))
        }
        root.addView(scroll(col))
    }

    /** Fallback when there's no transcript or no Claude key: write what you learned. */
    private fun showReflection() {
        clear(Session.QUIZ)
        val min = 250
        val col = column(center = false)
        col.addView(label("Write it down", 28f, Color.WHITE, bold = true))
        col.addView(label("The quiz isn't available for this video, so write the 3 most useful things you learned and how you'll use one of them today. At least $min characters.", 15f, MUTED), lp(top = 8))
        val et = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            hint = "1. …"
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(CARD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        col.addView(et, lp(top = 16))
        val count = label("0 / $min", 13f, MUTED)
        col.addView(count, lp(top = 6))
        val submit = button("Submit", ORANGE) {
            Session.finish(this, "Wrote reflection", et.text.toString())
            showDone("Saved. Your phone is yours.")
        }
        submit.isEnabled = false
        submit.alpha = 0.4f
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.toString()?.trim()?.length ?: 0
                count.text = "$len / $min"
                submit.isEnabled = len >= min
                submit.alpha = if (len >= min) 1f else 0.4f
            }
        })
        col.addView(submit, lp(top = 16))
        root.addView(scroll(col))
    }

    // ------------------------------------------------------------------ emergency + done

    private fun showEmergencyDialog() {
        val et = EditText(this).apply {
            hint = "Type the sentence exactly"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@LockActivity).apply { text = "\"$EMERGENCY_PHRASE\"" })
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency exit")
            .setMessage("For real emergencies only. Calls always work without this. Skipping is recorded in your history.")
            .setView(box)
            .setPositiveButton("Unlock") { _, _ ->
                val typed = et.text.toString().trim().trimEnd('.').lowercase()
                if (typed == EMERGENCY_PHRASE.lowercase()) {
                    Session.finish(this, "Emergency exit")
                    showDone("Unlocked. Tomorrow's session will run as normal.")
                } else {
                    toast("That doesn't match.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDone(msg: String) {
        clear("done")
        val col = column()
        col.addView(label(msg, 20f, Color.WHITE, bold = true))
        col.addView(button("Close", ORANGE) { finish() }, lp(top = 24))
        root.addView(scroll(col))
    }

    // ------------------------------------------------------------------ helpers

    private fun clear(newScreen: String) {
        stopTicker()
        destroyWeb()
        progressBar = null
        progressText = null
        playBtn = null
        extBtn = null
        topBar = null
        bottomBar = null
        quizStatus = null
        ui.removeCallbacks(hideRunnable)
        if (newScreen != Session.VIDEO) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        root.removeAllViews()
        screen = newScreen
    }

    private fun stopTicker() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    private fun destroyWeb() {
        web?.let {
            it.stopLoading()
            it.removeJavascriptInterface("Android")
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    private fun showMsg(i: Intent?) {
        val msg = i?.getStringExtra("msg") ?: return
        i.removeExtra("msg")
        toast(msg)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun column(center: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (center) Gravity.CENTER else Gravity.TOP
        setPadding(dp(24), dp(48), dp(24), dp(32))
    }

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun button(text: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rounded(color)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
}
