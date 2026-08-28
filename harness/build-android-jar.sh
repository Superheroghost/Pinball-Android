#!/usr/bin/env bash
# Builds a stub android.jar from harness/android-stubs so the headless compile
# check (build-app.sh) can run without an Android SDK install.
#
# Usage:
#   ./harness/build-android-jar.sh            # just (re)build the stub jar
#   ./harness/build-android-jar.sh then-build # also run build-app.sh against it
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/home/user/tools}"

JDK="$TOOLS/jdk"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
STDLIB="$TOOLS/libs/kotlin-stdlib.jar"
OUT="$ROOT/build/android-stub-classes"
JAR="$ROOT/build/android.jar"

echo ">> compiling android stubs"
export JAVA_HOME="$JDK"
rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/harness/android-stubs" -name '*.kt' > /tmp/android-stub-sources.txt
if ! "$KOTLINC" -classpath "$STDLIB" -d "$OUT" @/tmp/android-stub-sources.txt 2>/tmp/kotlinc-stub-err.log; then
  grep -v "^warning:" /tmp/kotlinc-stub-err.log
  exit 1
fi
grep -v "^warning:" /tmp/kotlinc-stub-err.log || true

echo ">> packing $JAR"
python3 - "$OUT" "$JAR" <<'EOF'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(base, f)
            z.write(full, os.path.relpath(full, src))
print("packed", dst)
EOF

if [ "${1:-}" = "then-build" ]; then
  ANDROID_JAR="$JAR" "$ROOT/harness/build-app.sh"
fi
