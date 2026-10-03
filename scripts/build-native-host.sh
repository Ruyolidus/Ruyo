#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/build/native-host
ruyo_jdk="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -Wno-misleading-indentation -fPIC -shared \
  -I"$ruyo_jdk/include" -I"$ruyo_jdk/include/linux" \
  app/src/main/cpp/patch_repair.cpp app/src/main/cpp/repair_jni.cpp \
  -o app/build/native-host/libruyo_repair.so
