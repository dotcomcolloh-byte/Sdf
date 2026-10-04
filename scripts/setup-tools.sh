#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update
sudo apt-get install -y unzip wget ffmpeg python3-pip
python3 -m pip install --break-system-packages -U yt-dlp
# Android command line tools are installed into the user's home for reproducible local builds.
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip
  unzip -q -o /tmp/cmdline-tools.zip -d /tmp/android-tools
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv /tmp/android-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "Tools installed. Set ANDROID_HOME=$ANDROID_HOME before building."
