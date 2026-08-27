#!/usr/bin/env bash
# Headless compile-check of ALL app Kotlin sources against android.jar.
# Does not produce an APK; catches every compile error before Gradle runs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/home/user/tools}"

JDK="$TOOLS/jdk"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-platforms/android-36/android.jar}"
JBOX2D="$TOOLS/libs/jbox2d.jar"
STDLIB="$TOOLS/libs/kotlin-stdlib.jar"
OUT="$ROOT/build/app-check"

CP="$JBOX2D:$ANDROID_JAR:$STDLIB"
mkdir -p "$OUT"

find "$ROOT/app/src/main/java" -name '*.kt' > /tmp/app-sources.txt
echo ">> compile-checking app ($(wc -l < /tmp/app-sources.txt) files)"
rm -rf "$OUT"
mkdir -p "$OUT"
export JAVA_HOME="$JDK"
if ! "$KOTLINC" -classpath "$CP" -d "$OUT" @/tmp/app-sources.txt 2>/tmp/kotlinc-app-err.log; then
  echo ">> FAILED"
  grep -v "^warning:" /tmp/kotlinc-app-err.log
  exit 1
fi
echo ">> app compile OK"
