#!/bin/sh
# run-platform-suite.sh -- build + run the platform numerical-consistency suite on Linux/macOS.
#
# Same source and same hardened flags as run-platform-suite.ps1 (which CI uses on Windows).
# Kept as a POSIX shell script so ubuntu-24.04 / macos-13 / macos-14 jobs do not depend on
# PowerShell being installed.
#
# Usage: sh native/tests/platform/run-platform-suite.sh [lib-path] [strict-nan]
# Exit codes: 0 pass, 1 check failure, 2 usage/IO, 3 golden vectors missing.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../../.." && pwd)
out="${CAVA_SUITE_OUT:-$root/build/platform-suite"
golden="${CAVA_SUITE_GOLDEN:-$root/native/tests/vectors/fp_probe.txt"
lib="${1:-${CAVA_SUITE_LIB:-}"
extra=""
if [ "${2:-" = "strict-nan" ]; then extra="--strict-nan"; fi

CXX="${CXX:-${CAVA_SUITE_CXX:-}"
if [ -z "$CXX" ]; then
  for c in g++ c++ clang++; do
    if command -v "$c" >/dev/null 2>&1; then CXX="$c"; break; fi
  done
fi
if [ -z "$CXX" ]; then echo "MISSING: no C++ compiler (tried g++, c++, clang++)"; exit 2; fi
echo "compiler: $CXX"

mkdir -p "$out"
exe="$out/cava_platform_suite"

# Hardened flags: MUST stay identical to native/cmake/CavaFlags.cmake.
set -- -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -fno-math-errno -Wall -Wextra \
       -I "$root/native/include" -o "$exe" "$here/cava_platform_suite.cpp"
echo "srcs=1"
echo "build: $CXX $*"
"$CXX" "$@"
echo "BUILT: $exe ($(wc -c < "$exe") bytes)"

# Run from the repo root: the suite's default golden path is repo-root-relative.
cd "$root"
if [ -n "$lib" ]; then
  "$exe" --golden "$golden" --expect-rows 15456 --lib "$lib" $extra
else
  "$exe" --golden "$golden" --expect-rows 15456 $extra
fi
rc=$?
echo "exit=$rc"
exit $rc
