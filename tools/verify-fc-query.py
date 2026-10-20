# -*- coding: utf-8 -*-
"""
Function Calling 条件查询能力验收。
用法：python tools/verify-fc-query.py [port]   默认 18081
断言：
  A 组  条件查询（品种名 / 行业名 / 交易所名 / 合约代码片段 / 不带条件）
  B 组  工具/REST 返回字段完整性（含 exchangeName —— 曾经的 bug）
  C 组  AI 端到端（模型选工具 + 填参数 + 以库中数据作答，不编造）
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else '18081'
BASE = 'http://127.0.0.1:%s' % PORT
PASS = [0]
FAIL = [0]


def check(name, ok, detail=''):
    if ok:
        PASS[0] += 1
        print(u'  [PASS] %s' % name)
    else:
        FAIL[0] += 1
        print(u'  [FAIL] %s   %s' % (name, detail))


def get(path, **params):
    url = BASE + path + ('?' + urllib.parse.urlencode(params) if params else '')
    return json.loads(urllib.request.urlopen(url, timeout=120).read())


def rows_of(d):
    data = d.get('data')
    if isinstance(data, list):
        return data
    return (data or {}).get('records') or []


def ask(msg):
    url = BASE + '/api/ai/ask?' + urllib.parse.urlencode({'message': msg})
    d = json.loads(urllib.request.urlopen(url, timeout=600).read())
    return d.get('data') or {}


print('=' * 78)
print(u'A 组：条件查询（REST 直连，覆盖工具底层同一条 SQL）')

cases = [
    (u'按品种名「螺纹钢」', dict(keyword=u''), None, u'螺纹钢'),
]
r1 = get('/api/futureContractText/findByPage', page=1, size=10, keyword=u'螺纹钢')
check(u'A1 按品种名查 → 有结果', len(rows_of(r1)) >= 1, u'命中 %d' % len(rows_of(r1)))

r2 = get('/api/futureContractText/findByPage', page=1, size=10, keyword=u'黑色金属')
check(u'A2 按行业名「黑色金属」查 → 有结果（SQL 用 OR 匹配 industry_name）',
      len(rows_of(r2)) >= 1, u'命中 %d' % len(rows_of(r2)))

r3 = get('/api/futureContractText/findByPage', page=1, size=10, keyword=u'郑州商品交易所')
check(u'A3 按交易所名「郑州商品交易所」查 → 有结果',
      len(rows_of(r3)) >= 1, u'命中 %d' % len(rows_of(r3)))

r4 = get('/api/futureContractText/findByPage', page=1, size=10, keyword=u'RB')
check(u'A4 按合约代码片段「RB」查 → 有结果', len(rows_of(r4)) >= 1, u'命中 %d' % len(rows_of(r4)))

r5 = get('/api/futurecontract/findAllByPage', page=1, size=10)
check(u'A5 不带品种名查合约列表 → 返回全部（修复点：原来恒为 0 条）',
      len(rows_of(r5)) >= 1, u'命中 %d' % len(rows_of(r5)))

r6 = get('/api/futurecontract/findAllByPage', page=1, size=10, varietyName=u'螺纹')
check(u'A6 品种名支持部分匹配（原为等号精确匹配）',
      len(rows_of(r6)) >= 1, u'命中 %d' % len(rows_of(r6)))

print('=' * 78)
print(u'B 组：返回字段完整性')

row = rows_of(r1)[0] if rows_of(r1) else {}
ex = row.get('exchange') or {}
ex_name = ex.get('exchangeName') if isinstance(ex, dict) else None
check(u'B1 合约条款返回含 exchangeName（修复点：原来 @Results 漏映射，字段为 null）',
      bool(ex_name), u'exchange=%r' % (ex_name,))
tv = row.get('tradingVariety') or {}
check(u'B2 返回含品种名', bool(tv.get('varietyName')), u'variety=%r' % (tv.get('varietyName'),))
check(u'B3 返回含保证金条款', bool(row.get('minMargin')), u'minMargin=%r' % (row.get('minMargin'),))

print('=' * 78)
print(u'C 组：AI 端到端（含本地 7B 模型推理，较慢）')

print(u'  ... 提问：螺纹钢属于哪个交易所')
t0 = time.time()
a = ask(u'螺纹钢属于哪个交易所')
trace = a.get('toolTrace') or []
tools = [t.get('tool') for t in trace]
ans = (a.get('answer') or '')
print(u'      工具=%s 耗时=%.1fs' % (tools, time.time() - t0))
print(u'      答：%s' % ans.replace('\n', ' ')[:220])
check(u'C1 调用了查询类工具', len(tools) >= 1, u'tools=%s' % tools)
check(u'C2 答出库中真实交易所（郑州商品交易所）',
      u'郑州商品交易所' in ans, u'ans=%s' % ans[:120])
check(u'C3 未编造「上海期货交易所」（模型先验知识，与库不符）',
      u'上海期货交易所' not in ans and u'上期所' not in ans, u'ans=%s' % ans[:120])
check(u'C4 业务数据查询不再伪造「参考：《…》」文档名',
      u'参考：《' not in ans, u'ans=%s' % ans[:160])

print(u'  ... 提问：螺纹钢的保证金比例是多少')
t0 = time.time()
b = ask(u'螺纹钢的保证金比例是多少')
ans2 = (b.get('answer') or '')
tools2 = [t.get('tool') for t in (b.get('toolTrace') or [])]
print(u'      工具=%s 耗时=%.1fs' % (tools2, time.time() - t0))
check(u'C5 调用了合约条款查询工具', u'queryFuturesContractSpec' in tools2, u'tools=%s' % tools2)
check(u'C6 答案含库中真实保证金「6%」', u'6%' in ans2, u'ans=%s' % ans2[:160])

print('=' * 78)
print(u'D 组：来源标注一致性（伪造的删掉，合法的不能误伤）')
try:
    st = get('/api/ai/kb/status')
    data = st.get('data') or {}
    rag = data.get('rag') or {}
    kb_ok = bool(rag.get('available')) and (st.get('total') or 0) > 0
    if not kb_ok:
        print(u'  向量库 available=%s，已入库文档 %s 份' % (rag.get('available'), st.get('total')))
except Exception as e:
    kb_ok = False
    print(u'  向量库状态查询失败：%r' % e)

if not kb_ok:
    print(u'  [SKIP] D2 知识库不可用（Docker/pgvector 未启动），跳过「合法引用不被误伤」断言')
else:
    print(u'  ... 提问：套期保值的保证金收取标准是什么')
    t0 = time.time()
    c = ask(u'套期保值的保证金收取标准是什么')
    tools3 = [t.get('tool') for t in (c.get('toolTrace') or [])]
    ans3 = (c.get('answer') or '')
    hit = any(t.get('tool') == 'searchKnowledgeBase'
              and (t.get('result') or '').find(u'未检索到') < 0
              for t in (c.get('toolTrace') or []))
    print(u'      工具=%s 耗时=%.1fs' % (tools3, time.time() - t0))
    print(u'      答：%s' % ans3.replace('\n', ' ')[:200])
    check(u'D1 知识库问题调用了 searchKnowledgeBase',
          u'searchKnowledgeBase' in tools3, u'tools=%s' % tools3)
    check(u'D2 检索到内容时保留「参考：《文档名》」标注（过滤逻辑没误伤合法引用）',
          (u'参考：《' in ans3) if hit else True,
          u'hit=%s ans=%s' % (hit, ans3[:140]))

print('=' * 78)
print(u'结果： PASS=%d  FAIL=%d' % (PASS[0], FAIL[0]))
print(u'说明：范围条件（如「保证金比例超过10%的品种」）属于已知能力边界——'
      u'工具参数只有字符串关键字，模型无法表达比较条件，此类需求需新增专用工具。')
print('=' * 78)
sys.exit(1 if FAIL[0] else 0)
