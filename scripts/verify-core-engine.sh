#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -x "./gradlew" ]]; then
    echo "ERROR: Gradle wrapper './gradlew' is missing or not executable." >&2
    exit 1
fi

echo "Running Hydro verification: core-engine tests, app unit tests, and debug APK..."
./gradlew :core-engine:test :app:testDebugUnitTest :app:assembleDebug "$@"

echo "Verification complete."
