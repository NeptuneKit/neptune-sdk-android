#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_VERSION="8.8"
GRADLE_CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/neptune-sdk-android-wrapper/gradle-${GRADLE_VERSION}"
GRADLE_BIN="${GRADLE_CACHE_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"
GRADLE_ZIP="${GRADLE_CACHE_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [[ ! -x "${GRADLE_BIN}" ]]; then
  mkdir -p "${GRADLE_CACHE_DIR}"
  if [[ ! -f "${GRADLE_ZIP}" ]]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    curl -fsSL "${GRADLE_URL}" -o "${GRADLE_ZIP}"
  fi
  if [[ ! -d "${GRADLE_CACHE_DIR}/gradle-${GRADLE_VERSION}" ]]; then
    unzip -q "${GRADLE_ZIP}" -d "${GRADLE_CACHE_DIR}"
  fi
fi

exec "${GRADLE_BIN}" -p "${PROJECT_DIR}" "$@"
