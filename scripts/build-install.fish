#!/usr/bin/env fish
# Build the debug APK, install it on the attached device, and launch it.
set -l root (realpath (status dirname)/..)
cd $root; or exit 1
./gradlew assembleDebug; or exit 1
adb install -r app/build/outputs/apk/debug/app-debug.apk; or exit 1
adb shell am start -n com.justadri.wavrouter/.MainActivity
