#!/usr/bin/env bash
# 操作日志（AOP）功能验收：可复跑断言
# 用法： bash verify-operlog.sh [端口] [jar路径]
#   bash verify-operlog.sh 18082
#
# 覆盖：
#   A 正常请求 → 日志落库（模块/动作/方法/IP/耗时/成功 齐全）
#   B 异常请求 → 日志成功记录 success=0 + error_msg，且异常照原样抛回前端（不被吞）
#   C 敏感/大参数 → 不记参数（recordParams=false 场景本脚本未覆盖，代码里有开关）
#   D 业务不受影响：接口本身返回成功，日志是异步写的（请求不等落库）

PORT=${1:-18082}
JAR=${2:-target/SmartHedge_Risk_Hub-0.0.1-SNAPSHOT.jar}
BASE="http://127.0.0.1:${PORT}"
# MySQL 密码从环境变量 MYSQL_PASSWORD 注入，脚本内不落明文；用法：MYSQL_PASSWORD=xxx bash verify-operlog.sh [端口]
MYSQL="/d/mysql-8.0.16-winx64/bin/mysql.exe -uroot --password="${MYSQL_PASSWORD:-}" -N -B smarthedge_risk_hub"

PASS=0; FAIL=0
ok()   { echo "  ✅ PASS | $1"; PASS=$((PASS+1)); }
no()   { echo "  ❌ FAIL | $1"; FAIL=$((FAIL+1)); }
info() { echo "  ℹ️      | $1"; }

sql() { $MYSQL -e "$1" 2>/dev/null | tr -d '\r'; }

echo "=============================================="
echo " 操作日志（AOP）验收  端口=${PORT}"
echo "=============================================="

# ---------- 启动应用 ----------
if ! curl -s -m 2 "${BASE}/api/ai/help" >/dev/null 2>&1; then
  echo ">> 启动应用（后台）..."
  nohup java -jar "${JAR}" --server.port=${PORT} > /tmp/operlog-app.log 2>&1 &
  for i in $(seq 1 60); do
    sleep 2
    if curl -s -m 2 "${BASE}/api/ai/help" >/dev/null 2>&1; then break; fi
  done
fi
curl -s -m 3 "${BASE}/api/ai/help" >/dev/null 2>&1 \
  && ok "应用就绪 ${BASE}" || { echo "应用未启动，退出"; exit 1; }

# ---------- A 组：正常请求记日志 ----------
echo
echo "【A】正常请求 → 应记录 success=1"
BEFORE=$(sql "select count(*) from sys_oper_log")
info "请求前日志总数：${BEFORE}"

RESP=$(curl -s -m 20 -X POST "${BASE}/api/futureContractText/insertText" \
  -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: 203.0.113.77' \
  -d '{"contractId":21,"varietyName":"工业硅","varietyCode":"SI","exchangeName":"上海期货交易所","tradeUnit":"5吨/手","quoteUnit":"元/吨","minPriceMove":5,"limitRange":"±4%","contractMonths":"1-12月","tradeTime":"9:00-11:30","lastTradingDayRule":"合约月份第15个交易日","deliveryDateRule":"最后交易日后第3个交易日","minMargin":"合约价值的8%"}')
info "接口返回：${RESP}"

sleep 2   # 等异步线程落库
AFTER=$(sql "select count(*) from sys_oper_log")
[ "$AFTER" -gt "$BEFORE" ] && ok "新增了日志（${BEFORE} → ${AFTER}）" || no "日志条数未增加（${BEFORE} → ${AFTER}）"

ROW=$(sql "select module,action,method,ip,cost_ms,success from sys_oper_log order by log_id desc limit 1")
info "最新一条：${ROW}"
MODULE=$(echo "$ROW" | cut -f1)
ACTION=$(echo "$ROW" | cut -f2)
METHOD=$(echo "$ROW" | cut -f3)
IP=$(echo    "$ROW" | cut -f4)
COST=$(echo  "$ROW" | cut -f5)
SUCC=$(echo  "$ROW" | cut -f6)

[ "$MODULE" = "期货合约条款" ]      && ok "module 正确=${MODULE}"        || no "module 不对=${MODULE}"
[ "$ACTION" = "新增" ]              && ok "action 正确=${ACTION}"        || no "action 不对=${ACTION}"
[ "$METHOD" = "FuturesContractTextController.insertText" ] && ok "method 正确=${METHOD}" || no "method 不对=${METHOD}"
[ "$IP" = "203.0.113.77" ]          && ok "IP 取自 X-Forwarded-For=${IP}（代理场景能拿到真实IP）" || no "IP 不对=${IP}"
[ "$SUCC" = "1" ]                   && ok "success=1"                   || no "success 不对=${SUCC}"
[ -n "$COST" ] && [ "$COST" -ge 0 ] 2>/dev/null && ok "耗时已记录=${COST}ms" || no "耗时未记录=${COST}"

# ---------- B 组：异常请求 ----------
echo
echo "【B】抛异常的请求 → 应记 success=0 + error_msg，且异常照原样抛回"
B2=$(sql "select count(*) from sys_oper_log")
# 品种不存在 → insertText 内部 NPE → 500（用来验证异常分支确实被切面捕获）
CODE=$(curl -s -o /tmp/operlog-err.txt -w '%{http_code}' -m 20 -X POST "${BASE}/api/futureContractText/insertText" \
  -H 'Content-Type: application/json' \
  -d '{"contractId":21,"varietyName":"不存在的品种XYZ","varietyCode":"NOPE","exchangeName":"上海期货交易所","tradeUnit":"5吨/手"}')
info "HTTP 状态码=${CODE}（期望非 200，说明异常没被切面吞掉）"
[ "$CODE" != "200" ] && ok "异常原样抛回（HTTP ${CODE}），切面没有吞异常" || no "异常被吞了，竟返回 200"

sleep 2
ROW2=$(sql "select success,error_msg from sys_oper_log order by log_id desc limit 1")
SUCC2=$(echo "$ROW2" | cut -f1)
ERR2=$(echo "$ROW2" | cut -f2)
info "最新一条：success=${SUCC2} error_msg=${ERR2}"
[ "$SUCC2" = "0" ] && ok "异常被记为 success=0" || no "异常未记为失败（success=${SUCC2}）"
[ -n "$ERR2" ] && [ "$ERR2" != "NULL" ] && ok "error_msg 已记录异常原因" || no "error_msg 为空"

# ---------- C 组：清理本次测试数据 ----------
echo
echo "【C】清理本次写入的测试数据"
NEWID=$(sql "select max(text_id) from futures_contract_text where variety_id=(select variety_id from trading_variety where variety_code='SI')")
if [ -n "$NEWID" ] && [ "$NEWID" != "NULL" ]; then
  curl -s -m 10 "${BASE}/api/futureContractText/deleteText?textId=${NEWID}" >/dev/null
  sleep 1
  DEL=$(sql "select action from sys_oper_log order by log_id desc limit 1")
  [ "$DEL" = "删除" ] && ok "删除操作也被记录（action=${DEL}）" || no "删除未被记录（action=${DEL}）"
else
  info "未找到测试数据，跳过删除验证"
fi

# ---------- D 组：日志表不可用 → 主业务不受影响 ----------
# 这一组验证简历里那句"日志写入失败不影响主业务流程"：
# 把日志表改名让 insert 必然失败，业务接口必须照常返回成功。
echo
echo "【D】日志表不可用 → 主业务应照常成功（日志失败不影响业务）"
sql "RENAME TABLE sys_oper_log TO sys_oper_log_bak" >/dev/null 2>&1
RESP2=$(curl -s -m 20 -X POST "${BASE}/api/futureContractText/insertText" \
  -H 'Content-Type: application/json' \
  -d '{"contractId":21,"varietyName":"工业硅","varietyCode":"SI","exchangeName":"上海期货交易所","tradeUnit":"5吨/手","quoteUnit":"元/吨","minPriceMove":5,"limitRange":"±4%","contractMonths":"1-12月","tradeTime":"9:00-11:30","lastTradingDayRule":"合约月份第15个交易日","deliveryDateRule":"最后交易日后第3个交易日","minMargin":"合约价值的8%"}')
# 立刻把表改回来，无论上面断言成败
sql "RENAME TABLE sys_oper_log_bak TO sys_oper_log" >/dev/null 2>&1
info "日志表不可用时的接口返回：${RESP2}"
echo "$RESP2" | grep -q '"success":true' \
  && ok "日志表写不进去，业务接口依然返回成功 —— 日志失败不影响主流程" \
  || no "日志表失败时业务接口也失败了，说明异常没有兜住"
# 还原：确认表回来了
CNT_BACK=$(sql "select count(*) from sys_oper_log")
[ -n "$CNT_BACK" ] && [ "$CNT_BACK" -gt 0 ] && ok "日志表已还原（现有 ${CNT_BACK} 条）" || no "日志表未能还原，请手动检查 sys_oper_log_bak"

# 清掉 D 组产生的测试数据
LASTID=$(sql "select max(text_id) from futures_contract_text where variety_id=(select variety_id from trading_variety where variety_code='SI')")
if [ -n "$LASTID" ] && [ "$LASTID" != "NULL" ]; then
  curl -s -m 10 "${BASE}/api/futureContractText/deleteText?textId=${LASTID}" >/dev/null
  info "已清理本次测试写入的合约条款 text_id=${LASTID}"
fi

echo
echo "=============================================="
echo " 结果： PASS=${PASS}  FAIL=${FAIL}"
echo "=============================================="
[ "$FAIL" -eq 0 ] || exit 1
