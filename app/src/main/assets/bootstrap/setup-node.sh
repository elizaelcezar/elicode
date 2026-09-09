#!/bin/bash
# Optional toolchain setup, run INSIDE the EliCode Ubuntu shell.
# Node.js 20 LTS (ARM64) from NodeSource, then OpenCode via npm.
set -euo pipefail
if ! command -v node >/dev/null 2>&1; then
  apt-get update && apt-get install -y ca-certificates curl gnupg
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
    | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list
  apt-get update && apt-get install -y nodejs git
fi
node --version
npm --version
npm install -g opencode-ai
opencode version
