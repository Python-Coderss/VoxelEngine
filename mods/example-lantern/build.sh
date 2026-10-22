#!/bin/sh
# Builds mods/example-lantern/glow-lantern.jar against the compiled game.
# Run build-fast.bat (or mvn compile) first so target/classes exists.
set -e
cd "$(dirname "$0")"

CP="../../target/classes"
if [ ! -d "$CP" ]; then
  echo "[mod] $CP not found - build the game first (build-fast.bat)."
  exit 1
fi

rm -rf build
mkdir -p build/classes/META-INF/services

javac -cp "$CP" -d build/classes src/com/example/lantern/LanternMod.java
cp src/META-INF/services/com.voxel.api.VoxelMod build/classes/META-INF/services/
cp -R assets build/classes/assets

jar cf glow-lantern.jar -C build/classes .
echo "[mod] built $(pwd)/glow-lantern.jar"
echo "[mod] copy it into the game's mods/ folder to load it."
