#!/usr/bin/env fish
# Pull all WavRouter recordings into a local folder (default ./recordings).
set -l dest ./recordings
if test (count $argv) -gt 0
    set dest $argv[1]
end
mkdir -p $dest; or exit 1
adb pull /sdcard/Android/data/com.justadri.wavrouter/files/Music/. $dest
