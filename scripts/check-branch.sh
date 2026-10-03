#!/bin/sh
set -eu

allowed_branch=multiplayer-bridge
current_branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)

if [ "$current_branch" != "$allowed_branch" ]; then
    echo "This project must only be edited and committed on multiplayer-bridge." >&2
    echo "Use its dedicated checkout; do not create another project branch." >&2
    exit 1
fi
