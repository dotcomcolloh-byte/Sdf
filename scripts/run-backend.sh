#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export VIDTUBE_DATA="${VIDTUBE_DATA:-$ROOT/backend/cache}"
command -v yt-dlp >/dev/null || { echo "yt-dlp not found (run scripts/setup-tools.sh)"; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg not found (needed to merge video+audio)"; exit 1; }
exec "$ROOT/gradlew" -p "$ROOT" :backend:run
