#!/usr/bin/env bash
# Builds one account-manager jar per Minecraft version.
#
# Minecraft 26.x ships unobfuscated (no Yarn, no Mojang mappings published), so the
# mod is compiled straight against the client jar instead of going through Loom.
set -euo pipefail
cd "$(dirname "$0")"

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=(26.1.2 26.2 26.3)
fi

rm -rf build dist
mkdir -p build dist

echo "== compiling core =="
mkdir -p build/core-classes
javac --release 25 -encoding UTF-8 -Xlint:-options -d build/core-classes \
  $(find core/src -name '*.java' | sort)

for V in "${VERSIONS[@]}"; do
  echo "== $V =="
  CP=$(python3 tools/fetch.py "$V" .cache)
  mkdir -p "build/mod-$V/classes" "build/mod-$V/res"
  sed "s/__MC__/$V/" mod/src/main/resources/fabric.mod.json > "build/mod-$V/res/fabric.mod.json"

  javac --release 25 -encoding UTF-8 -proc:none -Xlint:-options \
    -d "build/mod-$V/classes" \
    -cp "$CP:build/core-classes" \
    $(find mod/src/main/java -name '*.java' | sort)

  jar --create --file "dist/account-manager-$V.jar" \
    -C "build/mod-$V/classes" . \
    -C build/core-classes . \
    -C "build/mod-$V/res" .

  echo "   -> dist/account-manager-$V.jar"
done

ls -l dist/
