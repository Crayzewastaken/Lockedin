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

# ---- Settings + dashboard (no session running; Gemini on, OpenRouter + Claude off; sample stats)
adb shell am force-stop $PKG
cat > lockedin2.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="active" value="false" />
    <boolean name="useGemini" value="true" />
    <boolean name="useOpenRouter" value="false" />
    <boolean name="useClaude" value="false" />
    <boolean name="useBuiltIn" value="true" />
</map>
XML
cat > stats.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="json">{&quot;watch&quot;:{&quot;Alex Hormozi&quot;:16200,&quot;Jeremy Miner&quot;:9300},&quot;videosBy&quot;:{&quot;Alex Hormozi&quot;:6,&quot;Jeremy Miner&quot;:4},&quot;videosDone&quot;:10,&quot;quizAttempts&quot;:12,&quot;quizPasses&quot;:9,&quot;quizCorrect&quot;:74,&quot;quizQuestions&quot;:96,&quot;bestPct&quot;:100,&quot;scores&quot;:[50,62,75,88,62,100,75,88,50,88,100,75],&quot;pauses&quot;:23,&quot;rewinds&quot;:41,&quot;latestJumps&quot;:17,&quot;skipAttempts&quot;:3,&quot;breaks&quot;:5,&quot;breakMin&quot;:35,&quot;emergencies&quot;:1,&quot;summaries&quot;:2}</string>
</map>
XML
adb push lockedin2.xml /data/local/tmp/lockedin.xml
adb push stats.xml /data/local/tmp/stats.xml
adb shell cp /data/local/tmp/lockedin.xml /data/data/$PKG/shared_prefs/lockedin.xml
adb shell cp /data/local/tmp/stats.xml /data/data/$PKG/shared_prefs/stats.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/stats.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs || true
adb shell am start -n $PKG/.MainActivity
sleep 4
adb exec-out screencap -p > $OUT/5-main-top.png
for i in 1 2 3 4 5; do
  adb shell input swipe 540 1900 540 700 400
  sleep 1
  adb exec-out screencap -p > $OUT/6-main-scroll-$i.png
done

# ---- Scroll check-in screen
adb shell am start -n $PKG/.CheckInActivity --es app com.instagram.android --ei min 17
sleep 3
adb exec-out screencap -p > $OUT/8-checkin.png
# pick "Nothing really" (last option) to show the 10-second wait
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-checkin.xml >/dev/null 2>&1
B=$(grep -oE 'text="[^"]*Nothing really[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' $OUT/ui-checkin.xml | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
if [ -n "$B" ]; then
  X1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/'); Y1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  X2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/'); Y2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  adb shell input tap $(((X1+X2)/2)) $(((Y1+Y2)/2))
  sleep 2
  adb exec-out screencap -p > $OUT/9-checkin-nothing.png
fi
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
