#!/usr/bin/env sh
# Renders content/ into build/. Needs Node 20 or newer, and EXCHANGE_NAME: the operator's name
# goes in the titles (docs/reference/white-label.md).
#   EXCHANGE_NAME="Crypto Exchange" ./build.sh         -> static site in build/
#   EXCHANGE_NAME="Crypto Exchange" ./build.sh serve   -> also serve it at http://localhost:4567
set -e
cd "$(dirname "$0")"
[ -d node_modules ] || npm ci --no-audit --no-fund
node build.mjs
if [ "$1" = "serve" ]; then
  echo "serving build/ at http://localhost:4567"
  python3 -m http.server 4567 --directory build
fi
