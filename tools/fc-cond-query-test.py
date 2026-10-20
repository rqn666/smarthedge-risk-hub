# -*- coding: utf-8 -*-
"""实测：Function Calling 能否按用户条件查库并返回结果。"""
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = 'http://127.0.0.1:18081'

QUERIES = [
    u'螺纹钢的保证金比例是多少',
    u'有哪些属于黑色金属的期货品种',
    u'现货螺纹钢的规格和产地是什么',
    u'保证金比例超过10%的品种有哪些',
    u'螺纹钢有哪些合约，最后交易日是什么时候',
]


def ask(msg):
    url = BASE + '/api/ai/ask?' + urllib.parse.urlencode({'message': msg})
    t0 = time.time()
    raw = urllib.request.urlopen(url, timeout=600).read()
    dt = time.time() - t0
    d = json.loads(raw)
    data = d.get('data') or {}
    print('=' * 78)
    print(u'【问】%s' % msg)
    print(u'【耗时】%.1fs   rounds=%s   hitRoundLimit=%s'
          % (dt, data.get('rounds'), data.get('hitRoundLimit')))
    trace = data.get('toolTrace') or []
    if not trace:
        print(u'  —— 未调用任何工具')
    for t in trace:
        print(u'  [工具] %s' % t.get('tool'))
        print(u'   参数: %s' % json.dumps(t.get('args'), ensure_ascii=False))
        print(u'   耗时: %sms' % t.get('costMs'))
        r = (t.get('result') or '').replace('\n', ' ')
        print(u'   返回: %s' % (r[:420] + (u' ...(截断)' if len(r) > 420 else '')))
    ans = (data.get('answer') or '').replace('\n', ' ')
    print(u'【答】%s' % (ans[:500] + (u' ...(截断)' if len(ans) > 500 else '')))


if __name__ == '__main__':
    for q in QUERIES:
        try:
            ask(q)
        except Exception as e:
            print('ERROR %s -> %r' % (q, e))
    print('=' * 78)
    print('DONE')
