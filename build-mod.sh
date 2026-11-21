#!/usr/bin/env bash
# Builds one account-manager jar per Minecraft version.
#
# 26.x ships unobfuscated (no Yarn, no Mojang mappings published), so those versions
# are compiled straight against the client jar instead of going through Loom.
#
# 1.21.x and older ship an obfuscated client jar, so those builds additionally:
#   * compile against a client jar and Fabric API deobfuscated with Mojang's official
#     mappings (tools/fetch.py + tools/remapper.py),
#   * rename the handful of API names that only 26.x has, with the sed pass below,
#   * remap the compiled classes to intermediary, which is what Fabric Loader resolves
#     mods against at runtime (tools/remapper.py mod).
set -euo pipefail
cd "$(dirname "$0")"

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=(1.21.11 26.1.2 26.2 26.3)
fi

rm -rf build dist
mkdir -p build dist

for V in "${VERSIONS[@]}"; do
  echo "== $V =="
  CP=$(python3 tools/fetch.py "$V" .cache)
  OBFUSCATED=$(python3 tools/remapper.py is-obfuscated "$V" .cache)

  if [ "$OBFUSCATED" = "yes" ]; then
    JAVA_RELEASE=21
    JAVA_MIN=21
    LOADER_MIN=">=0.17.0"
  else
    JAVA_RELEASE=25
    JAVA_MIN=25
    LOADER_MIN=">=0.19.3"
  fi

  # The shared core is compiled for the same Java release as the game build, so a
  # 1.21.x jar never carries Java 25 bytecode into a Java 21 runtime.
  echo "   compiling core for Java $JAVA_RELEASE"
  CORE=build/mod-$V/core-classes
  mkdir -p "$CORE"
  javac --release "$JAVA_RELEASE" -encoding UTF-8 -Xlint:-options -d "$CORE" \
    $(find core/src -name '*.java' | sort)

  SRC=build/mod-$V/src
  CLASSES=build/mod-$V/classes
  mkdir -p "$CLASSES" build/mod-$V/res
  sed -e "s/__MC__/$V/" -e "s/__JAVA__/$JAVA_MIN/" -e "s/__LOADER__/$LOADER_MIN/" \
    mod/src/main/resources/fabric.mod.json > "build/mod-$V/res/fabric.mod.json"

  if [ "$OBFUSCATED" = "yes" ]; then
    # Same mod, 1.21.x spellings: the drawing entry point, three drawing calls and
    # two Fabric API names changed in 26.x.
    cp -r mod/src/main/java "$SRC"
    find "$SRC" -name '*.java' -print0 | xargs -0 sed -i -E \
      -e 's/\bGuiGraphicsExtractor\b/GuiGraphics/g' \
      -e 's/\bextractRenderState\b/render/g' \
      -e 's/\bg\.text\(/g.drawString(/g' \
      -e 's/\bg\.centeredText\(/g.drawCenteredString(/g' \
      -e 's/\bg\.outline\(/g.renderOutline(/g' \
      -e 's/net\.fabricmc\.fabric\.api\.client\.keymapping\.v1\.KeyMappingHelper/net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper/g' \
      -e 's/\bKeyMappingHelper\.registerKeyMapping\(/KeyBindingHelper.registerKeyBinding(/g' \
      -e 's/\bScreens\.getWidgets\(/Screens.getButtons(/g'

    # A 26.x-only name left behind means the pass above is out of date; say so loudly
    # instead of failing later with a wall of javac errors.
    if LEFTOVER=$(grep -rnE 'GuiGraphicsExtractor|extractRenderState|\.centeredText\(|\bg\.text\(|\bg\.outline\(|KeyMappingHelper|Screens\.getWidgets' "$SRC"); then
      echo "error: 26.x-only names survived the 1.21.x rename pass:" >&2
      echo "$LEFTOVER" >&2
      exit 1
    fi
  else
    SRC=mod/src/main/java
  fi

  javac --release "$JAVA_RELEASE" -encoding UTF-8 -proc:none -Xlint:-options \
    -d "$CLASSES" \
    -cp "$CP:$CORE" \
    $(find "$SRC" -name '*.java' | sort)

  # Named -> intermediary for obfuscated versions, a plain copy for 26.x.
  jar --create --file "build/mod-$V/compiled.jar" -C "$CLASSES" .
  python3 tools/remapper.py mod "$V" .cache \
    "build/mod-$V/compiled.jar" "build/mod-$V/mod.jar"

  FINAL="build/mod-$V/final"
  mkdir -p "$FINAL"
  (cd "$FINAL" && unzip -qo ../mod.jar && rm -rf META-INF)

  jar --create --file "dist/account-manager-$V.jar" \
    -C "$FINAL" . \
    -C "$CORE" . \
    -C "build/mod-$V/res" .

  # A jar that needs a newer Java than it declares crashes at startup instead.
  python3 tools/classversion.py "dist/account-manager-$V.jar" "$JAVA_MIN"

  echo "   -> dist/account-manager-$V.jar"
done

ls -l dist/
