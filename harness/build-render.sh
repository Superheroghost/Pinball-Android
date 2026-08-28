#!/usr/bin/env bash
# Headless build & run of the GL renderer against a software rasteriser.
#
# The app's Kotlin is compiled unchanged; only the *platform* is replaced:
# harness/android-stubs provides android.opengl.GLES20 (a small software
# rasteriser), android.opengl.Matrix, android.util.Log, GLSurfaceView.Renderer
# and the Khronos types. No android.jar and no emulator needed.
#
# Usage: ./build-render.sh [RenderTestMain|HudInsetsTestMain]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/home/user/tools}"

JDK="$TOOLS/jdk"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
JBOX2D="$TOOLS/libs/jbox2d.jar"
STDLIB="$TOOLS/libs/kotlin-stdlib.jar"
OUT="$ROOT/build/render-out"

CP="$JBOX2D:$STDLIB"

APP="$ROOT/app/src/main/java/com/superheroghost/neonpinball"
SRC="$(mktemp)"
{
  find "$APP/sim" -name '*.kt'
  find "$APP/gl" -name '*.kt'
  # Renderer-side game layer only: these are the files the GL surface drives.
  echo "$APP/game/PinballRenderer.kt"
  echo "$APP/game/Particles.kt"
  echo "$APP/game/GameSession.kt"
  echo "$APP/game/GameLoop.kt"
  echo "$APP/game/InputState.kt"
  echo "$APP/game/GameController.kt"
  echo "$APP/game/HudView.kt"
  find "$ROOT/harness/android-stubs" -name '*.kt'
  find "$ROOT/harness/render-src" -name '*.kt'
} > "$SRC"

echo ">> compiling renderer + stubs ($(wc -l < "$SRC") files)"
export JAVA_HOME="$JDK"
rm -rf "$OUT"
mkdir -p "$OUT"
if ! "$KOTLINC" -classpath "$CP" -d "$OUT" @"$SRC" 2>/tmp/kotlinc-render-err.log; then
  grep -v "^warning:" /tmp/kotlinc-render-err.log
  exit 1
fi
grep -v "^warning:" /tmp/kotlinc-render-err.log || true

MAIN="${1:-RenderTestMain}"
echo ">> running $MAIN"
exec "$JDK/bin/java" -cp "$OUT:$CP" "com.superheroghost.neonpinball.harness.$MAIN"
