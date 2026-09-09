#!/bin/bash
# EliCode PRoot loader reference.
# The app builds this command line itself (see ProotLauncher.kt); this script
# documents the equivalent manual invocation inside the Terminal tab.
#
# Usage: PROOT_BIN=<path> ROOTFS=<path> ./proot-loader.sh -c "git status"
set -euo pipefail
PROOT_BIN="${PROOT_BIN:?set PROOT_BIN to the proot binary}"
ROOTFS="${ROOTFS:?set ROOTFS to the ubuntu tree}"
ELICODE_HOME="${ELICODE_HOME:-$ROOTFS/../home}"
ELICODE_TMP="${ELICODE_TMP:-$ROOTFS/../tmp}"
PROJECTS="${PROJECTS:-$ROOTFS/../..//projects}"

export LD_LIBRARY_PATH="$(dirname "$PROOT_BIN")/lib:${LD_LIBRARY_PATH:-}"
export PROOT_TMPDIR="$ELICODE_TMP"

exec "$PROOT_BIN" \
  -r "$ROOTFS" \
  -0 \
  --kernel-release=5.15.0 \
  -b /dev -b /proc -b /sys \
  -b "$ELICODE_HOME:/root" \
  -b "$ELICODE_TMP:/tmp" \
  -b "$PROJECTS:/projects" \
  -w /projects \
  /bin/bash --login "$@"
