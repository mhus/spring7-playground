#!/usr/bin/env bash
#
# Build a native binary of the ai-assistant module via GraalVM native-image.
#
# Prereqs:
#   - GraalVM CE/EE for JDK 25 installed (SDKMAN: `sdk install java 25-graalce`)
#   - native-image tool must be available under $GRAALVM_HOME/bin/
#
# Usage:
#   ./build-native.sh              # use SDKMAN path $HOME/.sdkman/candidates/java/25-graalce
#   GRAALVM_HOME=/other/path ./build-native.sh
#
# First-run note:
#   The Spring Boot native profile executes:
#     1) process-aot (generates reflection/resource hints from the Spring context)
#     2) native-image (compiles the actual binary — takes 3-10 minutes)
#   If something fails at runtime ("class not accessible for reflection"), add
#   reachability metadata to ai-assistant/src/main/resources/META-INF/native-image/
#   or ensure the corresponding Spring AI / library dependency provides its own hints.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="ai-assistant"
GRAALVM="${GRAALVM_HOME:-$HOME/.sdkman/candidates/java/25-graalce}"

if [ ! -x "$GRAALVM/bin/native-image" ]; then
    echo "ERROR: native-image not found at: $GRAALVM/bin/native-image" >&2
    echo
    echo "Install GraalVM 25 via SDKMAN:" >&2
    echo "  sdk install java 25-graalce" >&2
    echo "Or set GRAALVM_HOME to point at your installation." >&2
    exit 1
fi

echo "▸ Using GraalVM: $GRAALVM"
export JAVA_HOME="$GRAALVM"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_ROOT"

echo "▸ Step 1/2: build and install module dependencies (jar mode)"
mvn -q -pl "$MODULE" -am clean install -DskipTests

echo
echo "▸ Step 2/2: native-image compile (this takes a few minutes)"
mvn -pl "$MODULE" -Pnative native:compile -DskipTests

BINARY="$PROJECT_ROOT/$MODULE/target/$MODULE"

echo
if [ -x "$BINARY" ]; then
    SIZE="$(du -sh "$BINARY" | awk '{print $1}')"
    echo "✓ Native binary: $BINARY"
    echo "  Size: $SIZE"
    echo
    echo "Run it from the project root (so data/ paths work):"
    echo "  $BINARY"
else
    echo "✗ Expected binary not found at: $BINARY" >&2
    echo "Check the maven output above for the actual failure." >&2
    exit 1
fi
