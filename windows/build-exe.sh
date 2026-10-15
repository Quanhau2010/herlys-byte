#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
JAR="dist/HerlysByte-Windows.jar"
[[ -f "$JAR" ]] || { echo "Missing $JAR; build the Windows JAR first." >&2; exit 1; }
command -v x86_64-w64-mingw32-gcc >/dev/null || { echo "Install MinGW-w64 (x86_64-w64-mingw32-gcc) first." >&2; exit 1; }
command -v x86_64-w64-mingw32-windres >/dev/null || { echo "Install MinGW-w64 binutils (windres) first." >&2; exit 1; }
RESOURCE_OBJ="$(mktemp --suffix=.o)"
trap 'rm -f "$RESOURCE_OBJ"' EXIT
x86_64-w64-mingw32-windres windows/HerlysByte.rc -O coff -o "$RESOURCE_OBJ"
x86_64-w64-mingw32-gcc -Os -s -municode -mwindows windows/HerlysByteLauncher.c "$RESOURCE_OBJ" -ladvapi32 -o dist/HerlysByte.exe
file dist/HerlysByte.exe
ls -lh dist/HerlysByte.exe
