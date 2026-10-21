#!/bin/sh
set -e
GRADLE_VERSION="8.9"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/lector-wrapper"
DIST="$CACHE/gradle-$GRADLE_VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$CACHE"
  TMP="$CACHE/gradle.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TMP"
  else
    wget -q "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$TMP"
  fi
  rm -rf "$DIST" "$CACHE/gradle-$GRADLE_VERSION"
  unzip -q "$TMP" -d "$CACHE"
  rm -f "$TMP"
fi
exec "$DIST/bin/gradle" "$@"
