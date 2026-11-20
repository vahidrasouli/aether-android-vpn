#!/bin/sh
# Minimal POSIX Gradle Wrapper launcher (Gradle 8.9).
# If gradle/wrapper/gradle-wrapper.jar is absent (offline-generated repo),
# run `gradle wrapper --gradle-version 8.9` once on a host with Gradle installed
# or let GitHub Actions `gradle/actions/setup-gradle@v4` execute the pinned version.

set -eu
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="${APP_HOME}/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "${WRAPPER_JAR}" ]; then
  if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
  fi
  echo "ERROR: ${WRAPPER_JAR} is missing and 'gradle' is not in PATH." >&2
  echo "Bootstrap once with: gradle wrapper --gradle-version 8.9" >&2
  exit 1
fi

exec java ${GRADLE_OPTS:-} -classpath "${WRAPPER_JAR}" org.gradle.wrapper.GradleWrapperMain "$@"
