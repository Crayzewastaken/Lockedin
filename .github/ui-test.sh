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
    <int name="positionSec" value="1200" />
    <int name="watchedSec" value="1200" />
    <boolean name="useBuiltIn" value="true" />
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
  # tap the middle to show the controls, then capture
  SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
  W=${SIZE%x*}; H=${SIZE#*x}
  ROT=$(adb shell dumpsys input | grep -m1 -oE 'SurfaceOrientation: [0-9]' | grep -oE '[0-9]$')
  if [ "$ROT" = "1" ] || [ "$ROT" = "3" ]; then T=$W; W=$H; H=$T; fi
  adb shell input tap $((W/2)) $((H/2))
  sleep 1.5
  adb exec-out screencap -p > $OUT/$1.png
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
sleep 4
shot 2-landscape
adb shell settings put system user_rotation 3
sleep 4
shot 3-landscape-other-way
adb shell settings put system user_rotation 0
sleep 4
shot 4-portrait-again

adb logcat -d > $OUT/logcat-full.txt
grep -E "FATAL|AndroidRuntime|$PKG" $OUT/logcat-full.txt | head -200 > $OUT/logcat-app.txt
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > $OUT/activity.txt
exit 0
