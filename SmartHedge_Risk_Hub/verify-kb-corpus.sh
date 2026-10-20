#!/usr/bin/env bash
# 知识库语料入库验收（SmartHedge_Risk_Hub）
# 验证：语料 PDF 已解析切分入库、向量维度正确、业务关键词可检索且带引用溯源
# 用法: bash verify-kb-corpus.sh [端口]     默认 18081
set -u
PORT=${1:-18081}
BASE="http://127.0.0.1:${PORT}/api/ai/kb"
PASS=0; FAIL=0
ok(){ echo "  [PASS] $1"; PASS=$((PASS+1)); }
ng(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
q(){ docker exec pg-vec psql -U postgres -d vec_db -t -A -c "$1" 2>/dev/null | tr -d '\r'; }

echo "=============================================="
echo " 知识库语料验收    port=${PORT}"
echo "=============================================="

echo
echo "--- A. 向量库规模 ---"
DOCS=$(q "select count(*) from kb_document")
CHUNKS=$(q "select count(*) from kb_chunk")
CHARS=$(q "select sum(length(content)) from kb_chunk")
echo "  文档数=${DOCS}   分块数=${CHUNKS}   知识块总字数=${CHARS}"
[ "${DOCS:-0}" -ge 16 ] && ok "文档数 >= 16" || ng "文档数 >= 16（实际 ${DOCS:-0}）"
[ "${CHUNKS:-0}" -ge 140 ] && ok "分块数 >= 140" || ng "分块数 >= 140（实际 ${CHUNKS:-0}）"

echo
echo "--- B. 全部入库成功（无失败文档）---"
BAD=$(q "select count(*) from kb_document where status <> 'INDEXED'")
[ "${BAD:-1}" -eq 0 ] && ok "失败文档数 = 0" || ng "失败文档数 = 0（实际 ${BAD:-?}）"

echo
echo "--- C. 向量维度与索引文件数 ---"
DIM=$(q "select vector_dims(embedding) from kb_chunk limit 1")
[ "${DIM:-0}" = "1024" ] && ok "向量维度 = 1024（bge-m3）" || ng "向量维度 = 1024（实际 ${DIM:-?}）"
PDFN=$(ls /d/apesource/SmartHedge_Risk_Hub/kb-corpus/*.pdf 2>/dev/null | wc -l | tr -d ' ')
[ "${PDFN:-0}" -ge 15 ] && ok "语料 PDF 文件数 = ${PDFN}" || ng "语料 PDF 文件数 >= 15（实际 ${PDFN:-0}）"

echo
echo "--- D. 业务关键词检索命中 ---"
for kw in 保证金比例 涨跌停板 最后交易日 统一社会信用代码 供应商评级 应急处置; do
  body=$(curl -s -m 60 --get --data-urlencode "query=${kw}" --data "topK=3" "${BASE}/search")
  n=$(printf '%s' "$body" | grep -o '"chunkIndex":[0-9]*' | wc -l | tr -d ' ')
  sc=$(printf '%s' "$body" | grep -o '"score":[0-9.]*' | head -1 | cut -d: -f2)
  if [ "$n" -ge 1 ]; then ok "搜「${kw}」命中 ${n} 条（首条相似度 ${sc:-?}）"; else ng "搜「${kw}」应有命中"; fi
done

echo
echo "--- E. 检索结果带引用溯源 ---"
body=$(curl -s -m 60 --get --data-urlencode "query=保证金比例" --data "topK=1" "${BASE}/search")
printf '%s' "$body" | grep -q '"document"'   && ok "含文档名 document"      || ng "含文档名 document"
printf '%s' "$body" | grep -q '"chunkIndex"' && ok "含块序号 chunkIndex"   || ng "含块序号 chunkIndex"
printf '%s' "$body" | grep -q '"score"'      && ok "含相似度 score"        || ng "含相似度 score"

echo
echo "--- F. 检索空词 / 无关词不炸 ---"
body=$(curl -s -m 60 --get --data-urlencode "query=zzzz不存在的词qqqq" --data "topK=3" "${BASE}/search")
printf '%s' "$body" | grep -q '"success":true' && ok "无关词查询正常返回（不报错）" || ng "无关词查询应正常返回"

echo
echo "=============================================="
echo " 结果： PASS=${PASS}  FAIL=${FAIL}"
echo "=============================================="
[ "$FAIL" -eq 0 ] || exit 1
