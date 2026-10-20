#!/usr/bin/env bash
# ============================================================
# ES 搜索功能验收脚本（可复跑）
#
# 用法：
#   1) 先启动应用：mvn spring-boot:run  (端口 8081)
#   2) bash verify-es.sh          # 默认打 8081
#      bash verify-es.sh 8080     # 指定端口（比如你自己 IDEA 里的实例）
#
# 校验三件事：
#   A. 全量同步：MySQL 条数 == ES 条数
#   B. 中文搜索：常见业务词能命中（验 IK 分词 + 别名字段）
#   C. 双写链路：新增/删除后 MySQL 与 ES 一致，重试表为空
# ============================================================
set -u

PORT="${1:-8081}"
BASE="http://127.0.0.1:$PORT"
ES="http://127.0.0.1:9200"
# Git Bash 环境有 http_proxy，本地请求必须绕过代理；用函数避免变量被 glob 展开
cget() { curl -s --noproxy '*' "$@"; }

# mysql 客户端：优先 PATH，其次探测常见安装位置（Windows 上通常不在 PATH 里）
MYSQL_BIN="$(command -v mysql || true)"
if [ -z "$MYSQL_BIN" ]; then
  for c in /d/mysql-8.0.16-winx64/bin/mysql.exe \
           "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
           /d/develop/mysql*/bin/mysql.exe; do
    if [ -x "$c" ]; then MYSQL_BIN="$c"; break; fi
  done
fi
if [ -z "$MYSQL_BIN" ]; then
  echo "[WARN] 未找到 mysql 客户端，C 段双写校验将跳过。"
  echo "       如需完整校验，请把 mysql.exe 加进 PATH，或改本脚本的 MYSQL_BIN。"
fi
# MySQL 密码从环境变量 MYSQL_PASSWORD 注入，脚本内不落明文；用法：MYSQL_PASSWORD=xxx bash verify-es.sh
MYSQL="$MYSQL_BIN -h127.0.0.1 -P3306 -uroot --password="${MYSQL_PASSWORD:-}" -N"
DB="smarthedge_risk_hub"

PASS=0
FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1 (期望=$3 实际=$2)"; else bad "$1 (期望=$3 实际=$2)"; fi; }

uenc() { python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1"; }
# 从 JSON 里取字段（$2 是 python 表达式，可访问 d）
jget() { python -c "
import sys,json
d=json.load(sys.stdin)
print($2)
" 2>/dev/null; }

echo "=========================================="
echo " A. 全量同步一致性"
echo "=========================================="
TMPJ="$(mktemp)"                    # 临时 JSON，避免硬编码 /tmp（Windows 下不可靠）
cget -X POST "$BASE/api/es/sync/full?recreate=true" > "$TMPJ" 2>/dev/null
python - "$TMPJ" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception as e:
    print('  [FAIL] 全量同步接口无响应:',e); raise SystemExit
rows=d.get('data') or []
allok=True
for r in rows:
    t=r.get('consistent')
    print(f"  [{'PASS' if t else 'FAIL'}] {r['index']:24s} MySQL={r.get('mysqlRows'):>3} ES={r.get('esDocs'):>3}")
    if not t: allok=False
print(f"  -> {len(rows)} 个索引, 全部一致={allok}")
PY

echo ""
echo "=========================================="
echo " B. 中文搜索命中（IK 分词 + 别名字段）"
echo "=========================================="
# 关键词:期望至少命中的条数
declare -A CASES=(
  ["螺纹钢"]=1
  ["保证金"]=1
  ["最后交易日"]=1
  ["交易单位"]=1
  ["涨跌停板"]=1
  ["行权价"]=1
  ["期权类型"]=1
  ["供应商"]=1
)
for kw in "${!CASES[@]}"; do
  n=$(cget "$BASE/api/es/search/multi?keyword=$(uenc "$kw")" | jget x "d['data']['total']")
  n=${n:-0}
  if [ "$n" -ge "${CASES[$kw]}" ]; then ok "搜「$kw」命中 $n 条"; else bad "搜「$kw」命中 $n 条（期望>=${CASES[$kw]}）"; fi
done

echo ""
echo "=========================================="
echo " C. 双写链路（新增 -> 一致；删除 -> 一致）"
echo "=========================================="
# 找一个未被占用的 contract_id
CID=""
if [ -n "$MYSQL_BIN" ]; then
  CID=$($MYSQL -e "use $DB; select contract_id from futures_contract where contract_id not in (select contract_id from futures_contract_text) limit 1;" 2>/dev/null)
fi
if [ -z "$MYSQL_BIN" ]; then
  echo "  [SKIP] 未找到 mysql 客户端，跳过双写测试"
elif [ -z "$CID" ]; then
  # 区分两种空：真的没有空闲 id，还是 MySQL 连不上/查询失败
  probe=$($MYSQL -e "use $DB; select count(*) from futures_contract;" 2>/dev/null)
  if [ -z "$probe" ]; then
    bad "MySQL 连不上或查询失败，双写测试无法执行（检查 $MYSQL_BIN 账号密码/服务状态）"
  else
    echo "  [SKIP] futures_contract_text 已覆盖全部 contract_id，无空闲 id 可插"
  fi
else
  before_mysql=$($MYSQL -e "use $DB; select count(*) from futures_contract_text;" 2>/dev/null)
  cget -X POST "$BASE/api/futureContractText/insertText" -H "Content-Type: application/json" \
    -d "{\"contractId\":$CID,\"varietyName\":\"工业硅\",\"varietyCode\":\"SI\",\"exchangeName\":\"广州期货交易所\",\"industryName\":\"能源化工\",\"minPriceMove\":4.5,\"tradeUnit\":\"验收脚本写入\",\"quoteUnit\":\"元/吨\",\"minMargin\":\"合约价值的9%\",\"limitRange\":\"+-6%\",\"contractMonths\":\"1月\",\"tradeTime\":\"上午9:00\",\"lastTradingDayRule\":\"验收\",\"deliveryDateRule\":\"验收\"}" > /dev/null
  sleep 2
  cget -X POST "$ES/futures_contract_text/_refresh" > /dev/null
  after_mysql=$($MYSQL -e "use $DB; select count(*) from futures_contract_text;" 2>/dev/null)
  after_es=$(cget "$ES/futures_contract_text/_count" | jget x "d['count']")
  check "新增后 MySQL 条数" "$after_mysql" "$((before_mysql+1))"
  check "新增后 ES 条数"    "$after_es"    "$after_mysql"

  # 主键回填校验：新记录的 text_id 应能在 ES 里按 _id 查到
  NEW_ID=$($MYSQL -e "use $DB; select text_id from futures_contract_text where contract_id=$CID;" 2>/dev/null)
  found=$(cget "$ES/futures_contract_text/_doc/$NEW_ID" | jget x "d.get('found')")
  check "ES 按 _id=$NEW_ID 能查到（主键回填）" "$found" "True"

  # 删除回收
  cget "$BASE/api/futureContractText/deleteText?textId=$NEW_ID" > /dev/null
  sleep 2
  cget -X POST "$ES/futures_contract_text/_refresh" > /dev/null
  del_mysql=$($MYSQL -e "use $DB; select count(*) from futures_contract_text;" 2>/dev/null)
  del_es=$(cget "$ES/futures_contract_text/_count" | jget x "d['count']")
  check "删除后 MySQL 回到基线" "$del_mysql" "$before_mysql"
  check "删除后 ES 回到基线"    "$del_es"    "$before_mysql"
fi

retry=$($MYSQL -e "use $DB; select count(*) from es_retry_task where status='PENDING';" 2>/dev/null)
check "重试表无 PENDING 积压" "${retry:-0}" "0"

echo ""
echo "=========================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "=========================================="
[ "$FAIL" -eq 0 ] && echo "全部通过 ✓" || echo "存在失败项，请检查上面的 [FAIL] 行"
exit $FAIL
