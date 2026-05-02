#!/usr/bin/env bash
# Build and run tele-cli-bridge. Stops any running instance first.
# Reads BOT_NAME / BOT_TOKEN from .env at the project root.
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/tele-cli-bridge-0.0.1.jar"
PROC_PATTERN="java -jar $JAR"

# 1. Stop any running instance.
if pgrep -f "$PROC_PATTERN" >/dev/null 2>&1; then
    pids=$(pgrep -f "$PROC_PATTERN" | tr '\n' ' ')
    echo "Stopping running instance (pid: $pids)..."
    kill $pids 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        sleep 1
        pgrep -f "$PROC_PATTERN" >/dev/null 2>&1 || break
    done
    if pgrep -f "$PROC_PATTERN" >/dev/null 2>&1; then
        echo "Process did not exit on SIGTERM; sending SIGKILL..."
        pkill -9 -f "$PROC_PATTERN" || true
        sleep 1
    fi
fi

# 2. Preflight checks.
command -v claude >/dev/null || { echo "ERROR: claude CLI not on PATH" >&2; exit 1; }
command -v gemini >/dev/null || { echo "ERROR: gemini CLI not on PATH" >&2; exit 1; }
[ -f ./.env ] || { echo "ERROR: .env not found at project root (need BOT_NAME, BOT_TOKEN)" >&2; exit 1; }

# 3. Source .env without echoing. Done before the build so JAVA_HOME (if set) applies to mvn too.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

# 4. If JAVA_HOME is set (env or .env), prefix its bin to PATH so mvn/java both pick it up.
if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/java" ] || { echo "ERROR: JAVA_HOME=$JAVA_HOME but $JAVA_HOME/bin/java is not executable" >&2; exit 1; }
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

# 5. Build.
echo "Building..."
mvn -s ./maven-settings.xml -DskipTests clean package -q

[ -f "$JAR" ] || { echo "ERROR: build did not produce $JAR" >&2; exit 1; }

# 6. Launch in background. LANG=C.UTF-8 is required so non-ASCII (cyrillic, etc.)
#    survives the JVM-to-subprocess argv encoding.
echo "Starting bot..."
LANG=C.UTF-8 LC_ALL=C.UTF-8 nohup java -jar "$JAR" > bot.log 2>&1 &
PID=$!
disown
echo "Started with PID $PID, logs at bot.log"

# 7. Wait briefly for startup signal.
for _ in $(seq 1 30); do
    if grep -q "Started BotApplication" bot.log 2>/dev/null; then
        echo "Bot startup OK."
        exit 0
    fi
    if grep -q "Application run failed" bot.log 2>/dev/null; then
        echo "Bot failed to start. Last 20 lines of bot.log:" >&2
        tail -20 bot.log >&2
        exit 1
    fi
    sleep 1
done
echo "Bot did not signal 'Started BotApplication' within 30s. Last 20 lines:" >&2
tail -20 bot.log >&2
exit 1
