#!/usr/bin/env bash
# ============================================================
# RAG（知识库检索）验收脚本 —— 可复跑
#
# 用法：
#   bash verify-rag.sh            # 默认打 18080，含端到端（较慢，约 1-2 分钟）
#   bash verify-rag.sh 18080      # 指定端口
#   bash verify-rag.sh 18080 fast # 跳过端到端，只验环境与检索
#
# 校验三件事：
#   A. 环境与数据：pgvector 可达、表存在、文档与分块数正确
#   B. 检索命中：用 PDF 里的专属事实检索（业务库里没有这些内容，
#      命中即证明走的是知识库而不是数据库）
#   C. 端到端：大模型自动调用 searchKnowledgeBase，答案含正确事实且带引用
#
# 前置条件：应用已启动、容器 pg-vec 在跑、Ollama 有 bge-m3
# ============================================================
set -u

PORT="${1:-18080}"
MODE="${2:-full}"
BASE="http://127.0.0.1:$PORT"
PG_CONTAINER="pg-vec"
PG_DB="vec_db"

PASS=0
FAIL=0
ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

# Git Bash 有 http_proxy，本地请求必须绕开；用函数避免变量被 glob 展开
cget() { curl -s --noproxy '*' "$@"; }
q()    { python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
psql() { docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

PDF="D:/apesource/SmartHedge_Risk_Hub/docs/kb/SmartHedge套期保值与风险管理制度手册.pdf"

echo "=========================================="
echo " A. 环境与数据"
echo "=========================================="

if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${PG_CONTAINER}$"; then
  bad "容器 $PG_CONTAINER 未运行（pgvector 起不来，知识库不可用）"
else
  ok "容器 $PG_CONTAINER 运行中"
fi

tbl=$(psql "select count(*) from information_schema.tables where table_name in ('kb_document','kb_chunk');")
if [ "${tbl:-0}" = "2" ]; then ok "知识库表存在（kb_document + kb_chunk）"; else bad "知识库表缺失（实际 $tbl 张）"; fi

ext=$(psql "select count(*) from pg_extension where extname='vector';")
if [ "${ext:-0}" = "1" ]; then ok "vector 扩展已启用"; else bad "vector 扩展未启用"; fi

# 应用侧健康
health=$(cget -m 10 "$BASE/api/ai/kb/status")
avail=$(echo "$health" | python -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('rag',{}).get('available'))" 2>/dev/null)
if [ "$avail" = "True" ]; then ok "应用侧 pgvector 连接可用"; else bad "应用侧 pgvector 不可用：$health"; fi

docs=$(echo "$health" | python -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('rag',{}).get('documents'))" 2>/dev/null)
chunks=$(echo "$health" | python -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('rag',{}).get('chunks'))" 2>/dev/null)
echo "  [INFO] 当前已入库：文档 ${docs:-0} 篇 / 分块 ${chunks:-0} 条"

# 没有已索引文档时自动上传一份，保证脚本可独立跑通
if [ "${docs:-0}" = "0" ]; then
  echo "  [INFO] 知识库为空，自动上传样本 PDF ..."
  if [ ! -f "/d/apesource/SmartHedge_Risk_Hub/docs/kb/SmartHedge套期保值与风险管理制度手册.pdf" ]; then
    bad "样本 PDF 不存在，先执行：python tools/gen-kb-pdf.py"
  else
    up=$(cget -m 300 -X POST "$BASE/api/ai/kb/upload" \
      -F "file=@$PDF" \
      -F "title=SmartHedge套期保值与风险管理制度手册" \
      -F "uploader=verify-rag")
    if echo "$up" | grep -q '"status":"INDEXED"'; then
      ok "样本 PDF 上传并索引成功"
    else
      bad "样本 PDF 上传失败：$up"
    fi
  fi
else
  ok "已有已索引文档（跳过上传）"
fi

final_chunks=$(psql "select count(*) from kb_chunk where embedding is not null;")
if [ "${final_chunks:-0}" -gt 0 ]; then ok "存在带向量的分块（$final_chunks 条）"; else bad "没有任何带向量的分块"; fi

echo ""
echo "=========================================="
echo " B. 检索命中（不依赖大模型）"
echo "=========================================="
# 查询:期望在返回内容里出现的证据关键词
# 键是问题，值是「必须出现在召回内容中」的事实 —— 这些事实只存在于 PDF，业务库里没有
declare -A CASES=(
  ["单笔敞口超过多少需要报董事会审批"]="5000"
  ["内部保证金标准比交易所加收多少"]="加收 2"
  ["A级供应商的授信额度"]="3000"
  ["浮动亏损多少要强制全部平仓"]="全部平仓"
  ["供应商年度复审时间要求"]="3 月 31"
  ["套保有效性达标线"]="80% 至 125%"
)
for question in "${!CASES[@]}"; do
  expect="${CASES[$question]}"
  resp=$(cget -m 60 "$BASE/api/ai/kb/search?query=$(q "$question")&topK=5")
  parsed=$(echo "$resp" | python -c "
import sys,json
try:
    d=json.load(sys.stdin); x=d.get('data') or {}
    recs=x.get('records') or []
    blob=' '.join((r.get('content') or '') for r in recs)
    print('%s\t%s\t%s' % (x.get('total',0), x.get('costMs'), 'YES' if '$expect' in blob else 'NO'))
except Exception:
    print('0\t-\tNO')
" 2>/dev/null)
  total=$(echo "$parsed" | cut -f1)
  cost=$(echo "$parsed" | cut -f2)
  hit=$(echo "$parsed" | cut -f3)

  if [ "${total:-0}" -gt 0 ] && [ "$hit" = "YES" ]; then
    ok "「$question」命中 $total 条/${cost}ms，含证据「$expect」"
  elif [ "${total:-0}" -eq 0 ]; then
    bad "「$question」未命中任何分块"
  else
    bad "「$question」命中 $total 条但召回内容不含「$expect」（阈值或分块需调优）"
  fi
done

echo ""
echo "=========================================="
echo " C. 端到端（大模型自动调工具 + 引用溯源）"
echo "=========================================="
if [ "$MODE" = "fast" ]; then
  echo "  [SKIP] fast 模式跳过端到端（本地 7B 模型一轮约 60-90s）"
else
  ans=$(cget -m 420 -G "$BASE/api/ai/ask" \
    --data-urlencode "message=按照公司制度，单笔套期保值敞口多大金额需要报董事会审批？" 2>/dev/null)

  tools=$(echo "$ans" | python -c "
import sys,json
d=json.load(sys.stdin); x=d.get('data') or {}
print(','.join(t.get('tool','') for t in (x.get('toolTrace') or [])))
" 2>/dev/null)
  text=$(echo "$ans" | python -c "
import sys,json
print((json.load(sys.stdin).get('data') or {}).get('answer') or '')
" 2>/dev/null)

  if echo "$tools" | grep -q "searchKnowledgeBase"; then
    ok "模型自动调用了 searchKnowledgeBase（工具: $tools）"
  else
    bad "模型未调用知识库工具（实际: ${tools:-无}）"
  fi
  if echo "$text" | grep -q "5000"; then
    ok "答案含正确事实「5000 万元」"
  else
    bad "答案未包含预期事实，回答：$(echo "$text" | head -c 200)"
  fi
  if echo "$text" | grep -q "参考：《"; then
    ok "答案带引用溯源「参考：《文档名》」"
  else
    bad "答案缺少引用溯源"
  fi
fi

echo ""
echo "=========================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "=========================================="
[ "$FAIL" -eq 0 ] && echo "全部通过 ✓" || echo "存在失败项，请检查上面的 [FAIL] 行"
exit $FAIL
