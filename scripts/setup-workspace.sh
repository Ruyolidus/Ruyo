#!/bin/sh
set -eu

project_root=$(git rev-parse --show-toplevel)
cd "$project_root"
./scripts/check-branch.sh

origin_url=$(git remote get-url origin)
case "$origin_url" in
    https://github.com/Ruyolidus/Ruyo.git|https://github.com/Ruyolidus/Ruyo|git@github.com:Ruyolidus/Ruyo.git|ssh://git@github.com/Ruyolidus/Ruyo.git) ;;
    *) echo "Expected the Ruyolidus/Ruyo repository as origin." >&2; exit 1 ;;
esac

git config --local core.hooksPath .githooks
git config --local remote.pushDefault origin
git config --local push.default nothing
git config --local --replace-all remote.origin.push refs/heads/multiplayer-bridge:refs/heads/multiplayer-bridge
git config --local --replace-all remote.origin.fetch +refs/heads/multiplayer-bridge:refs/remotes/origin/multiplayer-bridge
git config --local branch.multiplayer-bridge.remote origin
git config --local branch.multiplayer-bridge.merge refs/heads/multiplayer-bridge

echo "Configured this checkout for multiplayer-bridge only."
