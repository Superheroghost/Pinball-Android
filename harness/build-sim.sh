#!/usr/bin/env bash
# Headless build & run harness for the pinball simulation core.
#
# Compiles the pure-Kotlin sim (app/src/main/java/.../sim) plus harness mains
# against the local jbox2d jar and runs them on the JVM. This is what makes
# physics tuning possible without an emulator.
#
# Usage: ./build-sim.sh [MainClass] [args...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/home/user/tools}"

JDK="$TOOLS/jdk"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-platforms/android-36/android.jar}"
JBOX2D="$TOOLS/libs/jbox2d.jar"
STDLIB="$TOOLS/libs/kotlin-stdlib.jar"
OUT="$ROOT/build/sim-out"

CP="$JBOX2D:$ANDROID_JAR:$STDLIB"

mkdir -p "$OUT"
find "$ROOT/harness/src" -name '*.kt' > /tmp/harness-sources.txt
find "$ROOT/app/src/main/java/com/superheroghost/neonpinball/sim" -name '*.kt' > /tmp/sim-sources.txt
# Pure-Kotlin game-layer files (no android.* imports) compile into the harness
# so the full session flow can be tested headless.
GAME_SRC="$ROOT/app/src/main/java/com/superheroghost/neonpinball/game"
{
  cat /tmp/sim-sources.txt > /tmp/sim-all.txt
  cat >> /tmp/sim-all.txt <<EOF2
$GAME_SRC/GameSession.kt
$GAME_SRC/GameLoop.kt
$GAME_SRC/InputState.kt
EOF2
}
mv /tmp/sim-all.txt /tmp/sim-sources.txt

echo ">> compiling sim + harness ($(wc -l < /tmp/sim-sources.txt) sim files, $(wc -l < /tmp/harness-sources.txt) harness files)"
export JAVA_HOME="$JDK"
"$KOTLINC" -classpath "$CP" -d "$OUT" @/tmp/sim-sources.txt @/tmp/harness-sources.txt 2>/tmp/kotlinc-err.log
rc=$?
grep -v "^warning:" /tmp/kotlinc-err.log || true
if [ $rc -ne 0 ]; then exit $rc; fi

MAIN="${1:-HarnessMain}"
shift || true
echo ">> running $MAIN"
exec "$JDK/bin/java" -cp "$OUT:$CP" "com.superheroghost.neonpinball.harness.$MAIN" "$@"
