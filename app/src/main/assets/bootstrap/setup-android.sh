#!/bin/bash
# Optional Android toolchain setup, run INSIDE the EliCode Ubuntu shell.
# Installs Temurin JDK 17 + Gradle + Android cmdline-tools (ARM64).
# Large download (~1GB) — only needed to build APKs.
set -euo pipefail
TOOLS="${ANDROID_HOME:-/opt/android-sdk}"
apt-get update && apt-get install -y curl unzip openjdk-17-jdk-headless
java -version
if ! command -v gradle >/dev/null 2>&1; then
  curl -fL https://services.gradle.org/distributions/gradle-8.7-bin.zip -o /tmp/gradle.zip
  mkdir -p /opt/gradle && unzip -q /tmp/gradle.zip -d /opt/gradle
  ln -sf /opt/gradle/gradle-8.7/bin/gradle /usr/local/bin/gradle
fi
gradle --version
if [ ! -d "$TOOLS/cmdline-tools/latest" ]; then
  mkdir -p "$TOOLS/cmdline-tools"
  curl -fL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip
  unzip -q /tmp/cmdtools.zip -d "$TOOLS/cmdline-tools"
  mv "$TOOLS/cmdline-tools/cmdline-tools" "$TOOLS/cmdline-tools/latest"
fi
export ANDROID_HOME="$TOOLS"
export PATH="$TOOLS/cmdline-tools/latest/bin:$TOOLS/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;35.0.0"
echo "Android SDK ready at $TOOLS"
