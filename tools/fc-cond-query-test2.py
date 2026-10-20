# -*- coding: utf-8 -*-
"""补测：同一 SQL 的条件匹配能力 + 模型选对工具时的效果。"""
import json
import time
import urllib.parse
import urllib.request

BASE = 'http://127.0.0.1:18081'


def rest(path, **params):
    url = BASE + path + '?' + urllib.parse.urlencode(params)
    return json.loads(urllib.request.urlopen(url, timeout=60).read())


def show_rest(title, path, **params):
    print('=' * 78)
    print(u'[REST] %s' % title)
    d = rest(path, **params)
    data = d.get('data')
    rows = data if isinstance(data, list) else (data or {}).get('records') or []
    print(u'  命中 %d 条' % len(rows))
    for r in rows:
        tv = r.get('tradingVariety') or {}
        ex = r.get('exchange') or {}
        fc = r.get('futuresContract') or {}
        print(u'    text_id=%-3s 合约=%-8s 品种=%-5s 交易所=%-10s 保证金=%s'
              % (r.get('textId'), fc.get('contractCode'), tv.get('varietyName'),
                 ex.get('exchangeName'), r.get('minMargin')))


def ask(msg):
    url = BASE + '/api/ai/ask?' + urllib.parse.urlencode({'message': msg})
    t0 = time.time()
    d = json.loads(urllib.request.urlopen(url, timeout=600).read())
    data = d.get('data') or {}
    print('=' * 78)
    print(u'【问】%s' % msg)
    print(u'【耗时】%.1fs  rounds=%s' % (time.time() - t0, data.get('rounds')))
    for t in data.get('toolTrace') or []:
        print(u'  [工具] %s 参数=%s' % (t.get('tool'), json.dumps(t.get('args'), ensure_ascii=False)))
        r = (t.get('result') or '').replace('\n', ' ')
        print(u'   返回: %s' % (r[:400] + (u' ...(截断)' if len(r) > 400 else '')))
    if not data.get('toolTrace'):
        print(u'  —— 未调用任何工具')
    ans = (data.get('answer') or '').replace('\n', ' ')
    print(u'【答】%s' % (ans[:400] + (u' ...(截断)' if len(ans) > 400 else '')))


if __name__ == '__main__':
    show_rest(u'按行业名「黑色金属」模糊匹配', '/api/futureContractText/findByPage',
              page=1, size=10, keyword=u'黑色金属')
    show_rest(u'按交易所名「郑州商品交易所」模糊匹配', '/api/futureContractText/findByPage',
              page=1, size=10, keyword=u'郑州商品交易所')
    show_rest(u'按合约代码「RB」模糊匹配', '/api/futureContractText/findByPage',
              page=1, size=10, keyword=u'RB')
    for q in [u'黑色金属行业有哪些期货品种的合约条款',
              u'螺纹钢属于哪个交易所，保证金和涨跌停板分别是多少']:
        try:
            ask(q)
        except Exception as e:
            print('ERROR %s -> %r' % (q, e))
    print('=' * 78)
    print('DONE')
