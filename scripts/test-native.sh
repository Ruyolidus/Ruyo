#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/build/native-host app/build/test-previews
c++ -std=c++17 -O2 -Wall -Wextra -Werror -Wno-misleading-indentation -Iapp/src/main/cpp \
 app/src/main/cpp/patch_repair.cpp app/src/test/cpp/patch_repair_test.cpp -o app/build/native-host/repair-test
app/build/native-host/repair-test app/build/test-previews
