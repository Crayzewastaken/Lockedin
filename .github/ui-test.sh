#!/usr/bin/env bash
# Runs on the Android emulator in CI: puts the app straight into the video screen
# (as if a session were running, 20 min watched), then screenshots it upright and sideways.
set -x
OUT=screens
mkdir -p $OUT
PKG=com.lockedin.morning

adb root || true
sleep 3
adb wait-for-device
adb install -r -g LockedIn.apk
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put system accelerometer_rotation 0
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system user_rotation 0
adb shell wm size > $OUT/wm-size.txt

TODAY=$(date +%F)
cat > lockedin.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="true" />
    <string name="phase">VIDEO</string>
    <string name="sessionDay">$TODAY</string>
    <string name="videoId">aqz-KE-bpKQ</string>
    <string name="videoTitle">Test video for layout check</string>
    <string name="videoAuthor">CI</string>
    <int name="videoDuration" value="2700" />
    <int name="positionSec" value="300" />
    <int name="watchedSec" value="1200" />
    <boolean name="useBuiltIn" value="true" />
    <boolean name="uiTestMode" value="true" />
    <boolean name="workEnabled" value="false" />
</map>
EOF
adb push lockedin.xml /data/local/tmp/lockedin.xml
APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell mkdir -p /data/data/$PKG/shared_prefs
adb shell cp /data/local/tmp/lockedin.xml /data/data/$PKG/shared_prefs/lockedin.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell chmod 771 /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/lockedin.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs || true

adb logcat -c
adb shell am start -n $PKG/.LockActivity
sleep 20

shot() {
  # tap near the top-left of the video area (valid both upright and sideways) to show controls
  adb shell input tap 500 450
  sleep 1.5
  adb exec-out screencap -p > $OUT/$1.png
  # and one without tapping (controls should appear by themselves right after rotating)
}

# Portrait
shot 1-portrait
# Rewind 10s twice so the "furthest" marker and Latest button show
SIZE=$(adb shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1)
shot 1b-portrait-controls
# Pause (controls stay up), rewind a bit, screenshot showing the "furthest" marker
adb shell input keyevent KEYCODE_WAKEUP
sleep 1

# Landscape
adb shell settings put system user_rotation 1
sleep 2
adb exec-out screencap -p > $OUT/2a-landscape-just-rotated.png
sleep 4
shot 2-landscape
adb shell settings put system user_rotation 3
sleep 4
shot 3-landscape-other-way
adb shell settings put system user_rotation 0
sleep 4
shot 4-portrait-again

# ---- Morning chooser (session just started, nothing picked yet)
adb shell am force-stop $PKG
cat > lockedin3.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="true" />
    <string name="phase">PICKING</string>
    <string name="sessionDay">$TODAY</string>
    <boolean name="pickChosen" value="false" />
    <string name="channels">Alex Hormozi, Jeremy Miner, Codie Sanchez</string>
    <boolean name="workEnabled" value="false" />
    <boolean name="uiTestMode" value="true" />
</map>
XML
adb push lockedin3.xml /data/local/tmp/lockedin.xml
adb shell cp /data/local/tmp/lockedin.xml /data/data/$PKG/shared_prefs/lockedin.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell am start -n $PKG/.LockActivity
sleep 4
adb exec-out screencap -p > $OUT/14-chooser.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-chooser.xml >/dev/null 2>&1
tap_text() {
  B=$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" $2 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [ -n "$B" ]; then
    X1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/'); Y1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
    X2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/'); Y2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
    adb shell input tap $(((X1+X2)/2)) $(((Y1+Y2)/2))
  fi
}
tap_text "Codie Sanchez" $OUT/ui-chooser.xml
tap_text "45–60 min" $OUT/ui-chooser.xml
sleep 1
adb exec-out screencap -p > $OUT/14b-chooser-selected.png
adb shell input swipe 540 1900 540 900 300
sleep 1
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-chooser2.xml >/dev/null 2>&1
tap_text "Show me videos" $OUT/ui-chooser2.xml
sleep 25
adb exec-out screencap -p > $OUT/15-video-list.png

# ---- First-run setup guide
setup_at() {
  adb shell am force-stop $PKG
  cat > lockedin4.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="false" />
    <boolean name="onboarded" value="false" />
    <int name="setupStep" value="$1" />
    <boolean name="workEnabled" value="true" />
</map>
XML
  adb push lockedin4.xml /data/local/tmp/lockedin.xml
  adb shell cp /data/local/tmp/lockedin.xml /data/data/$PKG/shared_prefs/lockedin.xml
  adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
  adb shell am start -n $PKG/.MainActivity
  sleep 4
}
setup_at 0
adb exec-out screencap -p > $OUT/20-setup-welcome.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-setup.xml >/dev/null 2>&1
tap_text "Get started" $OUT/ui-setup.xml
sleep 1.5
adb exec-out screencap -p > $OUT/21-setup-overlay.png
setup_at 3
adb exec-out screencap -p > $OUT/22-setup-accessibility.png
setup_at 8
adb exec-out screencap -p > $OUT/23-setup-youtubers.png
setup_at 9
adb exec-out screencap -p > $OUT/24-setup-length.png
setup_at 10
adb exec-out screencap -p > $OUT/24b-setup-goal.png
setup_at 11
adb exec-out screencap -p > $OUT/25-setup-quiz.png
setup_at 12
adb exec-out screencap -p > $OUT/26-setup-work.png
setup_at 14
adb exec-out screencap -p > $OUT/27-setup-done.png

# ---- Settings + dashboard (no session running; Gemini on, OpenRouter + Claude off; sample stats)
adb shell am force-stop $PKG
cat > lockedin2.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="false" />
    <boolean name="onboarded" value="true" />
    <boolean name="useGemini" value="true" />
    <boolean name="useOpenRouter" value="false" />
    <boolean name="useClaude" value="false" />
    <boolean name="useBuiltIn" value="true" />
</map>
XML
MON=$(date -d "last monday" +%F); [ "$(date +%u)" = 1 ] && MON=$TODAY
TUE=$(date -d "$MON +1 day" +%F)
cat > stats.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="json">{&quot;watch&quot;:{&quot;Alex Hormozi&quot;:16200,&quot;Jeremy Miner&quot;:9300},&quot;videosBy&quot;:{&quot;Alex Hormozi&quot;:6,&quot;Jeremy Miner&quot;:4},&quot;videosDone&quot;:10,&quot;quizAttempts&quot;:12,&quot;quizPasses&quot;:9,&quot;quizCorrect&quot;:74,&quot;quizQuestions&quot;:96,&quot;bestPct&quot;:100,&quot;scores&quot;:[50,62,75,88,62,100,75,88,50,88,100,75],&quot;pauses&quot;:23,&quot;rewinds&quot;:41,&quot;latestJumps&quot;:17,&quot;skipAttempts&quot;:3,&quot;breaks&quot;:5,&quot;breakMin&quot;:35,&quot;emergencies&quot;:1,&quot;summaries&quot;:2,&quot;watchDays&quot;:{&quot;$MON&quot;:3900,&quot;$TUE&quot;:1500}}</string>
</map>
XML
adb push lockedin2.xml /data/local/tmp/lockedin.xml
adb push stats.xml /data/local/tmp/stats.xml
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" "<map>" "    <string name=\"time\">{&quot;$TODAY&quot;:{&quot;com.instagram.android&quot;:1800,&quot;com.zhiliaoapp.musically&quot;:900}}</string>" "</map>" > scroll.xml
adb push scroll.xml /data/local/tmp/scroll.xml
adb shell cp /data/local/tmp/scroll.xml /data/data/$PKG/shared_prefs/scroll.xml
adb shell chmod 660 /data/data/$PKG/shared_prefs/scroll.xml
adb shell cp /data/local/tmp/lockedin.xml /data/data/$PKG/shared_prefs/lockedin.xml
adb shell cp /data/local/tmp/stats.xml /data/data/$PKG/shared_prefs/stats.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/stats.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs || true
adb shell am start -n $PKG/.MainActivity
sleep 4
adb exec-out screencap -p > $OUT/5-home.png
adb shell input swipe 540 1900 540 700 400
sleep 1
adb exec-out screencap -p > $OUT/5b-home-scrolled.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-home.xml >/dev/null 2>&1
tap_text "See all stats  ▾" $OUT/ui-home.xml
sleep 1
adb exec-out screencap -p > $OUT/5c-home-allstats.png
adb shell input swipe 540 600 540 2000 200; adb shell input swipe 540 600 540 2000 200; adb shell input swipe 540 600 540 2000 200
sleep 1
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-home2.xml >/dev/null 2>&1
tap_text "⚙  Settings" $OUT/ui-home2.xml
sleep 1.5
adb exec-out screencap -p > $OUT/6-settings.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-settings.xml >/dev/null 2>&1
tap_text "&#128250;  YouTubers &amp; videos" $OUT/ui-settings.xml
sleep 1.5
adb exec-out screencap -p > $OUT/6b-settings-youtubers.png
adb shell input keyevent KEYCODE_BACK
sleep 1

# ---- Scroll check-in: "doom scrolling?" (no saved questions yet → breather)
adb shell am start -n $PKG/.CheckInActivity --es app com.instagram.android --ei min 17
sleep 3
adb exec-out screencap -p > $OUT/8-checkin-ask.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-checkin.xml >/dev/null 2>&1
tap_text "Yes" $OUT/ui-checkin.xml
sleep 2
adb exec-out screencap -p > $OUT/9-checkin-noquestions.png
adb shell am force-stop $PKG
sleep 1
# ---- Recall quiz (seed 3 questions "from yesterday")
YDAY=$(date -d yesterday +%F)
cat > bank.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="items">[{&quot;id&quot;:&quot;a1&quot;,&quot;q&quot;:&quot;According to Hormozi, what should you do before raising your prices?&quot;,&quot;options&quot;:[&quot;Increase the value of the offer&quot;,&quot;Run more ads&quot;,&quot;Lower your costs&quot;,&quot;Hire a sales team&quot;],&quot;answer&quot;:&quot;Increase the value of the offer&quot;,&quot;explain&quot;:&quot;Make the offer so good people feel stupid saying no.&quot;,&quot;at&quot;:&quot;12:30&quot;,&quot;title&quot;:&quot;\$100M Offers in 45 minutes&quot;,&quot;author&quot;:&quot;Alex Hormozi&quot;,&quot;day&quot;:&quot;$YDAY&quot;,&quot;wrong&quot;:1,&quot;seen&quot;:0,&quot;lastSeen&quot;:0},{&quot;id&quot;:&quot;a2&quot;,&quot;q&quot;:&quot;What does Jeremy Miner say to ask instead of pitching early?&quot;,&quot;options&quot;:[&quot;Problem-awareness questions&quot;,&quot;Closing questions&quot;,&quot;Price questions&quot;,&quot;Yes/no questions&quot;],&quot;answer&quot;:&quot;Problem-awareness questions&quot;,&quot;explain&quot;:&quot;Let the prospect discover the problem themselves.&quot;,&quot;at&quot;:&quot;08:10&quot;,&quot;title&quot;:&quot;NEPQ sales training&quot;,&quot;author&quot;:&quot;Jeremy Miner&quot;,&quot;day&quot;:&quot;$YDAY&quot;,&quot;wrong&quot;:0,&quot;seen&quot;:0,&quot;lastSeen&quot;:0},{&quot;id&quot;:&quot;a3&quot;,&quot;q&quot;:&quot;How many leads did Hormozi say to contact per day when starting out?&quot;,&quot;options&quot;:[&quot;100&quot;,&quot;10&quot;,&quot;1,000&quot;,&quot;25&quot;],&quot;answer&quot;:&quot;100&quot;,&quot;explain&quot;:&quot;Volume beats talent early on.&quot;,&quot;at&quot;:&quot;31:05&quot;,&quot;title&quot;:&quot;\$100M Leads&quot;,&quot;author&quot;:&quot;Alex Hormozi&quot;,&quot;day&quot;:&quot;$YDAY&quot;,&quot;wrong&quot;:0,&quot;seen&quot;:0,&quot;lastSeen&quot;:0}]</string>
</map>
XML
adb shell am force-stop $PKG
adb push bank.xml /data/local/tmp/bank.xml
adb shell cp /data/local/tmp/bank.xml /data/data/$PKG/shared_prefs/bank.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/bank.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs || true
adb shell am start -n $PKG/.CheckInActivity --es app com.instagram.android --ei min 18
sleep 3
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-ask2.xml >/dev/null 2>&1
tap_text "No" $OUT/ui-ask2.xml
sleep 2
adb exec-out screencap -p > $OUT/12-recall-q1.png

tap_first_option() {
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  adb pull /data/local/tmp/ui.xml $OUT/ui-recall.xml >/dev/null 2>&1
  # the 4 options are the clickable TextViews; tap the first one
  B=$(grep -oE 'clickable="true"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' $OUT/ui-recall.xml | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [ -n "$B" ]; then
    X1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/'); Y1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
    X2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/'); Y2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
    adb shell input tap $(((X1+X2)/2)) $(((Y1+Y2)/2))
  fi
}
tap_first_option; sleep 0.3
adb exec-out screencap -p > $OUT/12b-recall-answered.png
sleep 1.5
tap_first_option; sleep 1.5
tap_first_option; sleep 2
adb exec-out screencap -p > $OUT/13-recall-result.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-result.xml >/dev/null 2>&1
tap_text "Try again" $OUT/ui-result.xml
sleep 2
adb exec-out screencap -p > $OUT/13b-recall-tryagain.png
# back button should NOT work mid-quiz, but works on the result screen
adb shell input keyevent KEYCODE_BACK
sleep 2

# dashboard again, now with a logged check-in
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity
sleep 4
adb shell input swipe 540 1900 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/10-dashboard-scrolling.png
adb shell input swipe 540 1900 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/10b-dashboard-scrolling.png
for i in 1 2 3 4; do adb shell input swipe 540 1900 540 600 300; sleep 1; done
adb exec-out screencap -p > $OUT/11-settings-checkins.png
adb shell input swipe 540 1900 540 600 300; sleep 1
adb exec-out screencap -p > $OUT/11b-settings-checkins.png

adb logcat -d > $OUT/logcat-full.txt
grep -E "FATAL|AndroidRuntime|$PKG" $OUT/logcat-full.txt | head -200 > $OUT/logcat-app.txt
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > $OUT/activity.txt
exit 0
