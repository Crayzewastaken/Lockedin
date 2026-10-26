# Locked In

Your first unlock each morning opens a 30+ minute Alex Hormozi or Jeremy Miner video. The phone stays locked on it until you've watched it start to finish and passed a 5-minute quiz on what was in it.

## How it works

**When it starts**
- On your first unlock of the day between 4:00 and 13:00 (you can change the window).
- If an alarm is due within the next 2 hours, it waits. That covers checking the time before your alarm, snoozing, and so on. It starts on the first unlock after your alarms are done.
- It works with any clock app that sets a normal alarm (Google Clock, Samsung Clock, etc.).

**The video**
- The app picks a random long video from the two channels, between 30 and 90 minutes (you can change this). It never repeats one until it has gone through them all.
- It only picks videos that have captions, because the quiz is written from the transcript.
- It plays full screen and starts automatically. The only control is Pause/Play. You can't seek, speed up or skip, and only real watching time counts.
- If you open any other app, the video pauses and you're sent straight back to it.

**Breaks**
- You get 15 minutes of breaks per day in total, up to 3 breaks: 5, 10 or 15 minutes each (so 3×5, 10+5, or one 15).
- During a break you have your phone back. A countdown shows in your notifications, and the video returns on its own when the timer ends.
- Settings and app uninstalling stay blocked even during a break, so you can't switch the app off.

**Messages (Messenger, SMS)**
- Notification pop-ups show on top of the video as normal.
- You can open a messaging app only if a new message from it arrived in the last 10 minutes. That stops you using "checking messages" as an excuse to scroll.
- Each visit is capped at 2 minutes, and all visits are capped at 5 minutes per session. Then you're sent back.
- The video is paused while you're away, so this time doesn't count as watching.
- Phone calls and alarms always work.

**The quiz**
- Gemini (free) writes 8 multiple-choice questions spread across the whole video, about specifics a person couldn't guess. It writes them in the background while you watch.
- You have 5 minutes. The pass mark is 70%.
- If you fail, it shows what you missed (with the timestamp) and gives you a brand-new quiz.
- Gemini can watch the YouTube video directly when there's no transcript. If the AI can't be reached at all, you write a 250+ character summary of what you learned instead.

**Safety valves**
- **Emergency exit:** you type a long sentence to unlock. It's recorded in your history.
- **No internet:** after 20 minutes of failing to load a video, a "skip today" button appears. This is also recorded.

The settings screen shows your last 7 days: passed, skipped, how long your breaks were, how much message time you used, and how many quiz tries it took.

## Build the APK

### Option A — GitHub builds it for you (no PC setup)
1. Create a free GitHub account if you need one, then make a new **private** repository.
2. Upload everything in this folder to it. On github.com you can use "Add file → Upload files" and drag in the unzipped folder contents, including the hidden `.github` folder. If the web uploader skips `.github`, create the file `.github/workflows/build.yml` by hand and paste in its contents.
3. Go to the **Actions** tab. The "Build APK" run starts on its own, or you can press "Run workflow". It takes about 5 minutes.
4. Open the finished run and download **LockedIn-apk** from "Artifacts". Unzip it to get `app-debug.apk`.

### Option B — Android Studio
1. Install Android Studio and open this folder.
2. Let it sync, then go to Build → Build App Bundle(s)/APK(s) → Build APK(s).
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install and set up (on your phone)
1. Copy the APK to your phone and open it. Allow "install unknown apps" for whichever app you opened it from.
2. Open **Locked In** and go through the 5 setup steps on the main screen:
   1. Display over other apps
   2. Accessibility → Locked In → On
   3. Notification access → Locked In
   4. Show notifications
   5. Battery → Unrestricted

   **On Android 13 and newer**, steps 2 and 3 are greyed out ("Restricted setting") for apps installed outside the Play Store. To fix it: try step 2 once, go back, open App info (there's a button for it in the app), tap ⋮ in the top right, choose **Allow restricted settings**, then turn it on.
3. Paste your free **Gemini API key**: aistudio.google.com/apikey → Create API key. No card needed; the free tier easily covers one quiz a day. (A paid Claude key also works if you prefer.)
4. Optional: add a **YouTube Data API key**. It's free from Google Cloud Console → enable "YouTube Data API v3" → Credentials. Without it, the app finds videos through YouTube search, which usually works but is less reliable.
5. Tap **Start a session now (test)** to try it.

### Samsung / Xiaomi / Oppo users
These phones are aggressive about killing background apps. Also turn off "Put app to sleep" / "Auto-start manager" restrictions for Locked In. If the lock ever stops triggering, check that Accessibility → Locked In is still on.

## Settings you can change
- Claude model
- Which channels to pull from, plus extra video links
- Shortest and longest video length
- The lock window hours
- How close an alarm has to be for the lock to wait
- Number of quiz questions, time limit and pass mark
- Which messaging apps are allowed, plus their time limits

Settings can't be changed while a session is running.

Default messaging package names:
- Messenger: `com.facebook.orca`
- Google Messages: `com.google.android.apps.messaging`
- Samsung Messages: `com.samsung.android.messaging`

To add WhatsApp, add `com.whatsapp`.

## Honest limitations
- **Android won't let a normal app truly lock the phone.** This uses the strongest tool available to a sideloaded app: an accessibility service that pulls you back every time you leave. A determined person could get around it, for example with safe mode or ADB. It's built to beat a groggy 6am you, not a hacker.
- **Video finding and transcripts use YouTube's unofficial app endpoints.** YouTube changes these now and then. If videos stop loading, the YouTube Data API key fixes the finding part. Transcript fetching may need a small code update if YouTube changes it. When that happens, the app falls back to the written summary rather than locking you out.
- **YouTube ads may play.** Ads in embedded videos are rarer, but they can appear, and they can't be skipped because the player is shielded from taps.
