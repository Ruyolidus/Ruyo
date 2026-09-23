#!/usr/bin/env bash
set -u
ruyo_device_result=0
./gradlew --no-daemon :app:connectedDebugAndroidTest || ruyo_device_result=$?
mkdir -p app/build/translation-diagnostics
adb pull /sdcard/Download/ruyo-diagnostics/. app/build/translation-diagnostics/ || true
exit "$ruyo_device_result"
