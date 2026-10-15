#!/usr/bin/env bash
set -e
cd "$(dirname "$(readlink -f "$0")")"
if ! command -v mvn >/dev/null 2>&1; then
  echo "Không tìm thấy Maven. Cần cài JDK 21+ và Maven 3.9+." >&2
  exit 1
fi
exec mvn javafx:run
