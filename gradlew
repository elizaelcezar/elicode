#!/bin/sh
#
# EliCode Gradle wrapper startup script.
# Pinned to Gradle 8.14.3 (see gradle/wrapper/gradle-wrapper.properties).
# Generate the wrapper jar on the build machine with:
#   gradle wrapper --gradle-version 8.14.3
#
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  echo "gradle-wrapper.jar not found. Run: gradle wrapper --gradle-version 8.14.3" >&2
  echo "Falling back to system gradle if available..." >&2
  if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
  else
    exit 1
  fi
fi

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
