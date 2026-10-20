#!/usr/bin/env bash
# ============================================================
# AI 二期能力验收：SSE 流式输出 + 合约文本解析入库
#
# 用法：
#   bash verify-ai2.sh                # 默认端口 18081，完整模式（会调本地大模型，约 1~3 分钟）
#   bash verify-ai2.sh 8080           # 指定端口
#   bash verify-ai2.sh 18081 fast     # 快速模式：只跑不依赖大模型的断言（约 5 秒）
#
# 覆盖四段：
#   A. 工具注册    previewFuturesContractText / saveFuturesContractText 是否被 @Tool 扫到
#   B. 写操作闸门  缺失 / 伪造 / 重复 previewToken 必须被拒
#                  —— 这段最重要：它证明「不经预览就写库」在服务端不可能发生，
#                     而不是靠模型自觉遵守提示词
#   C. 流式输出    SSE 是否逐块推送（delta 块数 > 1）、是否上报工具事件、是否正常 done
#   D. 解析入库    AI 抽字段 → preview 拿 token → save 落库 → MySQL 条数 +1
#
# 依赖：应用已启动、MySQL 可连、Ollama 在跑（C/D 段需要）。
# ============================================================
set -u

PORT="${1:-18081}"
MODE="${2:-full}"
BASE="http://127.0.0.1:${PORT}"
DB="smarthedge_risk_hub"

# 本地请求必须绕过代理（Git Bash 环境常带 http_proxy）
cget() { curl -s --noproxy '*' "$@"; }

# MySQL 客户端自动探测：命令不在 PATH 时必须报 FAIL，
# 绝不能因为取不到数据就 SKIP —— 那会把"数据库连不上"伪装成"正常跳过"
MYSQL_BIN="${MYSQL_BIN:-}"
if [ -z "$MYSQL_BIN" ]; then
  for cand in /d/mysql-8.0.16-winx64/bin/mysql.exe "$(command -v mysql 2>/dev/null || true)"; do
    if [ -n "$cand" ] && [ -x "$cand" ]; then MYSQL_BIN="$cand"; break; fi
  done
fi
# MySQL 密码从环境变量 MYSQL_PASSWORD 注入，脚本内不落明文；用法：MYSQL_PASSWORD=xxx bash verify-ai2.sh
MYSQL="$MYSQL_BIN -h127.0.0.1 -P3306 -uroot --password="${MYSQL_PASSWORD:-}" -N"

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
skip() { echo "  [SKIP] $1"; SKIP=$((SKIP + 1)); }

INFO_FILE="verify-ai2-info.json"
RESULT_FILE="verify-ai2-result.txt"

echo "============================================================"
echo "AI 二期验收  base=$BASE  mode=$MODE"
echo "============================================================"

# ---------- 探活 ----------
CODE=$(cget -m 5 -o /dev/null -w "%{http_code}" "$BASE/api/ai/tools" 2>/dev/null)
if [ "$CODE" != "200" ]; then
  echo "应用未就绪（$BASE/api/ai/tools 返回 ${CODE:-无响应}），请先启动应用再跑本脚本。"
  exit 1
fi

MYSQL_OK=0
if [ -n "$MYSQL_BIN" ] && $MYSQL -e "select 1;" >/dev/null 2>&1; then
  MYSQL_OK=1
else
  echo "警告：MySQL 不可用（MYSQL_BIN=${MYSQL_BIN:-未找到}），D 段将报 FAIL 而不是跳过。"
fi

# ---------- A. 工具注册 ----------
echo ""
echo "A. 工具注册"
TOOLS=$(cget -m 10 "$BASE/api/ai/tools")
CNT=$(printf '%s' "$TOOLS" | grep -o '"name"' | wc -l | tr -d ' ')
[ "$CNT" -ge 6 ] && ok "工具总数 $CNT >= 6" || bad "工具总数 $CNT < 6（@Tool 扫描可能没生效）"

if printf '%s' "$TOOLS" | grep -q 'previewFuturesContractText'; then
  ok "previewFuturesContractText 已注册"
else
  bad "previewFuturesContractText 未注册"
fi

if printf '%s' "$TOOLS" | grep -q 'saveFuturesContractText'; then
  ok "saveFuturesContractText 已注册"
else
  bad "saveFuturesContractText 未注册"
fi

# ---------- B. 写操作闸门 ----------
echo ""
echo "B. 写操作闸门（服务端强制，不依赖模型自觉）"

R=$(cget -m 20 -X POST "$BASE/api/ai/contract/save" \
      -H 'Content-Type: application/json' -d '{"previewToken":""}')
if printf '%s' "$R" | grep -q '"success":false'; then
  ok "空 previewToken 被拒绝"
else
  bad "空 previewToken 未被拒绝：$(printf '%s' "$R" | head -c 120)"
fi

R=$(cget -m 20 -X POST "$BASE/api/ai/contract/save" \
      -H 'Content-Type: application/json' -d '{"previewToken":"deadbeef0000"}')
if printf '%s' "$R" | grep -q '"success":false'; then
  ok "伪造 previewToken 被拒绝（无法绕过预览直接写库）"
else
  bad "伪造 previewToken 未被拒绝：$(printf '%s' "$R" | head -c 120)"
fi

# ---------- C / D. 需要大模型的断言 ----------
if [ "$MODE" = "fast" ]; then
  echo ""
  echo "C. 流式输出"
  skip "fast 模式跳过（需调大模型）"
  echo ""
  echo "D. 解析入库"
  skip "fast 模式跳过（需调大模型）"
else
  # 找一个「还没录入条款」的合约，保证脚本可反复执行且不会撞唯一约束
  if [ "$MYSQL_OK" = "1" ]; then
    ROW=$($MYSQL -e "use $DB; select fc.contract_code, tv.variety_name, tv.variety_code, e.exchange_name
      from futures_contract fc
      join trading_variety tv on fc.variety_id = tv.variety_id
      left join exchange e on tv.exchange_id = e.exchange_id
      where fc.contract_id not in (select contract_id from futures_contract_text)
      limit 1;" 2>/dev/null)
    CODE_V=$(echo "$ROW" | awk -F'\t' '{print $1}')
    NAME_V=$(echo "$ROW" | awk -F'\t' '{print $2}')
    VCODE_V=$(echo "$ROW" | awk -F'\t' '{print $3}')
    EXCH_V=$(echo "$ROW" | awk -F'\t' '{print $4}')
  else
    CODE_V=""; NAME_V=""; VCODE_V=""; EXCH_V=""
  fi

  cat > "$INFO_FILE" <<JSON
{"contractCode":"${CODE_V:-}","varietyName":"${NAME_V:-}","varietyCode":"${VCODE_V:-}","exchangeName":"${EXCH_V:-}"}
JSON

  BEFORE=$($MYSQL -e "use $DB; select count(*) from futures_contract_text;" 2>/dev/null | tr -d ' \r')
  [ -z "$BEFORE" ] && BEFORE=-1

  # 注意：heredoc 已经占用 stdin（python 用 `-` 从 stdin 读脚本），
  # 这里绝不能再加 `< /dev/null`，两个 stdin 重定向会互相覆盖导致脚本读空。
  # PYTHONIOENCODING 必须设 utf-8，否则 Windows 下输出中文会按 GBK 编码报错。
  PYTHONIOENCODING=utf-8 \
  "/c/Users/rqn30/.workbuddy/binaries/python/versions/3.13.12/python.exe" \
    - "$BASE" "$INFO_FILE" > "$RESULT_FILE" 2>&1 <<'PY' || true
import sys, json, time, urllib.request, urllib.parse, urllib.error

base = sys.argv[1]
info = json.load(open(sys.argv[2], encoding="utf-8"))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
out = []

def emit(k, v):
    out.append("%s=%s" % (k, v))

def post(path, body):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data,
        headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with opener.open(req, timeout=560) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))

# ---- C. 流式 ----
delta = 0
tool_events = 0
done = None
err = None
try:
    url = base + "/api/ai/chat/stream?message=" + urllib.parse.quote("螺纹钢期货的交易单位是多少？")
    cur = None
    with opener.open(url, timeout=400) as r:
        for raw in r:
            line = raw.decode("utf-8", "ignore").rstrip("\n")
            if line.startswith("event:"):
                cur = line[6:].strip()
            elif line.startswith("data:"):
                if cur == "delta":
                    delta += 1
                elif cur in ("tool_start", "tool_end"):
                    tool_events += 1
                elif cur == "done":
                    try:
                        done = json.loads(line[5:].strip())
                    except Exception:
                        pass
                elif cur == "error":
                    err = line[5:].strip()
except Exception as e:
    emit("c_exception", str(e))

emit("c_delta", delta)
emit("c_tool_events", tool_events)
emit("c_done_rounds", (done or {}).get("rounds"))
emit("c_error", err or "")

# ---- D. 解析入库 ----
if not info.get("contractCode"):
    emit("d_ready", "0")
    emit("d_reason", "MySQL 不可用或没有空闲合约（每条合约只能有一条条款），无法构造导入文本")
else:
    emit("d_ready", "1")
    text = ("%s期货合约条款：合约代码 %s，交易品种 %s（品种代码 %s），上市交易所 %s。"
            "交易单位 10吨/手，报价单位 元/吨，最小变动价位 1。"
            "涨跌停板幅度为上一交易日结算价的±4%%。合约月份 1、3、5、7、9、11月。"
            "交易时间 上午9:00-11:30，下午13:30-15:00。"
            "最后交易日规则 合约交割月份的第10个交易日；交割日期规则 最后交易日后连续三个交易日。"
            "最低交易保证金 合约价值的5%%。所属行业 化工。") % (
        info["varietyName"], info["contractCode"], info["varietyName"],
        info["varietyCode"], info["exchangeName"])

    imp = post("/api/ai/contract/import", {"text": text, "dryRun": True})
    d = imp.get("data") or {}
    token = d.get("previewToken")
    emit("d_token", token or "")
    emit("d_rounds", d.get("rounds"))
    emit("d_answer", (d.get("answer") or "").replace("\n", " ")[:200])
    trace_tools = [t.get("tool") for t in (d.get("toolTrace") or [])]
    emit("d_tools", ",".join([str(t) for t in trace_tools]))
    # preview 阶段不应写库
    emit("d_preview_only", "1" if token else "0")

    if token:
        s1 = post("/api/ai/contract/save", {"previewToken": token})
        emit("d_save_success", s1.get("success"))
        emit("d_save_msg", str(s1.get("msg"))[:150])
        s2 = post("/api/ai/contract/save", {"previewToken": token})
        emit("d_reuse_success", s2.get("success"))
    else:
        emit("d_save_success", "NA")
        emit("d_reuse_success", "NA")

    # 把 preview 工具完整返回也存下来，便于排查校验失败原因
    for t in (d.get("toolTrace") or []):
        if t.get("tool") == "previewFuturesContractText":
            emit("d_preview_result", (t.get("result") or "")[:600])
            break

sys.stdout.write("\n".join(out))
PY

  # 读取 python 结果
  getv() { grep "^$1=" "$RESULT_FILE" 2>/dev/null | head -1 | cut -d= -f2- ; }

  echo ""
  echo "C. 流式输出（SSE）"
  C_DELTA=$(getv c_delta); C_TOOL=$(getv c_tool_events); C_ERR=$(getv c_error)
  if [ -n "$C_ERR" ]; then
    bad "SSE 返回 error 事件：$(printf '%s' "$C_ERR" | head -c 150)"
  fi
  if [ "${C_DELTA:-0}" -gt 1 ] 2>/dev/null; then
    ok "delta 块数 $C_DELTA > 1（逐块推送，不是一次性返回）"
  else
    bad "delta 块数 ${C_DELTA:-0} <= 1（疑似走了分片兜底或流式未生效）"
  fi
  if [ "${C_TOOL:-0}" -gt 0 ] 2>/dev/null; then
    ok "SSE 上报了 $C_TOOL 个工具事件（tool_start/tool_end）"
  else
    bad "SSE 未上报任何工具事件（应至少 1 个 tool_start + 1 个 tool_end）"
  fi
  [ -n "$(getv c_done_rounds)" ] && ok "收到 done 事件（rounds=$(getv c_done_rounds)）" || bad "未收到 done 事件"

  echo ""
  echo "D. 合约文本解析入库"
  if [ "$(getv d_ready)" != "1" ]; then
    bad "无法构造导入文本：$(getv d_reason)"
  elif [ "$MYSQL_OK" != "1" ]; then
    bad "MySQL 不可用，无法校验落库结果"
  else
    TOKEN=$(getv d_token)
    D_TOOLS=$(getv d_tools)
    [ -n "$TOKEN" ] && ok "AI 解析后拿到 previewToken=$TOKEN" || bad "未拿到 previewToken（未通过字典校验），preview 返回：$(getv d_preview_result)"
    printf '%s' "$D_TOOLS" | grep -q 'previewFuturesContractText' \
      && ok "模型自主调用了 previewFuturesContractText（toolTrace: $D_TOOLS）" \
      || bad "模型未调用 preview 工具（toolTrace: $D_TOOLS）"

    AFTER=$($MYSQL -e "use $DB; select count(*) from futures_contract_text;" 2>/dev/null | tr -d ' \r')
    if [ "$BEFORE" != "-1" ] && [ -n "$AFTER" ]; then
      if [ "$(getv d_save_success)" = "True" ] || [ "$(getv d_save_success)" = "true" ]; then
        if [ "$AFTER" -gt "$BEFORE" ]; then
          ok "落库成功：MySQL 条数 $BEFORE -> $AFTER"
        else
          bad "save 返回成功但条数没变（$BEFORE -> $AFTER）"
        fi
      else
        bad "save 失败：$(getv d_save_msg)"
      fi
    fi

    if [ "$(getv d_reuse_success)" = "False" ] || [ "$(getv d_reuse_success)" = "false" ]; then
      ok "previewToken 一次性生效（重复 save 被拒，防重复提交）"
    else
      bad "同一 previewToken 被重复使用（应拒）"
    fi
  fi
fi

# ---------- 清理 ----------
rm -f "$INFO_FILE" "$RESULT_FILE"

echo ""
echo "============================================================"
echo "PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
if [ "$FAIL" -eq 0 ]; then
  echo "全部通过 ✓"
else
  echo "存在失败项，请查看上方 [FAIL] 输出"
fi
echo "============================================================"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
