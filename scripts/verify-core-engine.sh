#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -x "./gradlew" ]] && [[ "${HYDRO_USE_GRADLE:-0}" == "1" ]]; then
    echo "Running Gradle test suite..."
    ./gradlew :core-engine:test :app:testDebugUnitTest
    exit 0
fi

TOOLCHAIN_DIR="${HYDRO_TOOLCHAIN_DIR:-/tmp/toolchain}"
OUT_DIR="${HYDRO_VERIFY_OUT:-/tmp/hydro-verify-build}"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN_DIR/jdk/jdk4py/java-runtime}"
KOTLINC="${KOTLINC:-$TOOLCHAIN_DIR/kotlin/bin/kotlinc}"
KLIB="${KLIB:-$TOOLCHAIN_DIR/kotlin/lib}"
JUNIT_JAR="${JUNIT_JAR:-$TOOLCHAIN_DIR/lib/junit-4-minimal.jar}"
ANDROID_STUBS_JAR="${ANDROID_STUBS_JAR:-$TOOLCHAIN_DIR/lib/android-room-stubs.jar}"

if [[ ! -x "$JAVA_HOME/bin/java" ]] || [[ ! -x "$KOTLINC" ]]; then
    echo "ERROR: JAVA_HOME ($JAVA_HOME) or kotlinc ($KOTLINC) not found." >&2
    echo "In a standard developer environment with Maven Central & Google Maven access, run:" >&2
    echo "  gradle :core-engine:test :app:testDebugUnitTest" >&2
    exit 1
fi

echo "[1/3] Compiling :core-engine main sources with kotlinc -Werror..."
find core-engine/src/main/kotlin -name "*.kt" | sort > "$OUT_DIR/main_sources.txt"
MAIN_CP="$KLIB/kotlin-stdlib.jar:$KLIB/kotlinx-coroutines-core-jvm.jar"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" -Werror -cp "$MAIN_CP" @"$OUT_DIR/main_sources.txt" -d "$OUT_DIR/core-engine-main.jar"

echo "[2/3] Compiling :core-engine unit tests with kotlinc -Werror..."
find core-engine/src/test/kotlin -name "*.kt" | sort > "$OUT_DIR/test_sources.txt"
TEST_CP="$MAIN_CP:$KLIB/kotlin-test.jar:$KLIB/kotlin-test-junit.jar:$JUNIT_JAR:$OUT_DIR/core-engine-main.jar"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" -Werror -cp "$TEST_CP" @"$OUT_DIR/test_sources.txt" -d "$OUT_DIR/core-engine-tests.jar"

echo "[3/3] Executing :core-engine unit test suite..."
"$JAVA_HOME/bin/java" -cp "$TEST_CP:$OUT_DIR/core-engine-tests.jar" org.junit.runner.JUnitCore --scan-jar="$OUT_DIR/core-engine-tests.jar"

if [[ -f "$ANDROID_STUBS_JAR" ]]; then
    echo "[4/4] Compiling & executing :app Room + LiteRT-LM unit tests with kotlinc -Werror..."
    find app/src/main/java/com/ferousco/hydro/data \
         app/src/main/java/com/ferousco/hydro/logging \
         app/src/main/java/com/ferousco/hydro/ai \
         app/src/test/java -name "*.kt" | sort > "$OUT_DIR/app_sources.txt"
    APP_CP="$TEST_CP:$ANDROID_STUBS_JAR"
    JAVA_HOME="$JAVA_HOME" "$KOTLINC" -Werror -cp "$APP_CP" @"$OUT_DIR/app_sources.txt" -d "$OUT_DIR/app-tests.jar"
    "$JAVA_HOME/bin/java" -cp "$APP_CP:$OUT_DIR/app-tests.jar" org.junit.runner.JUnitCore --scan-jar="$OUT_DIR/app-tests.jar"
fi
