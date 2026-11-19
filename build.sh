#!/usr/bin/env bash
# Builds dist/msa2ssid.jar (no external dependencies, Java 17+).
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build dist
mkdir -p build/classes dist

javac --release 17 -encoding UTF-8 -d build/classes $(find src -name '*.java' | sort)

jar --create --file dist/msa2ssid.jar --main-class dev.msa2ssid.Main -C build/classes .

echo "built dist/msa2ssid.jar"
