#!/usr/bin/env bash
# Function Calling 条件查询能力验收（含本地大模型端到端）
# 用法：bash verify-fc-query.sh [port]    默认 18081
set -u
PORT="${1:-18081}"
PY="C:/Users/rqn30/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
cd "$(dirname "$0")"
"$PY" ../tools/verify-fc-query.py "$PORT"
