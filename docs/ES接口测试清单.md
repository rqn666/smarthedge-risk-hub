# ES 搜索接口 · 测试清单

> 应用启动：`mvn spring-boot:run`（端口 8081）
> 已确认数据：futures_contract=7、futures_contract_text=2、options_contract=2、
> options_contract_text=2、supplier=3
>
> **注意**：ES 挂载在 `/api/es/**` 下。你在 IDEA 里自己的实例默认端口是 **8080**，
> 我起的验证实例是 **8081**。两个端口路径完全一样，把下面 URL 里的 8081 换成 8080 即可。

---

## 一、当前状态：ES 是"并行新接口"，老接口没动

| 接口类型 | 走哪 | 说明 |
|---|---|---|
| 老接口 `findByPage` / `findAll` 等 11 个 | **MySQL LIKE** | 原有代码没改，ES 无关 |
| 新接口 `/api/es/search*` | **Elasticsearch** | 本次新增 |

**两套并行，互不影响。** 好处是你原前端联调不受影响；后续要切全量走 ES，
逐个把 Controller 转发到 ES 即可（见文末"如何切换"）。

---

## 二、ES 搜索接口（3 个）

### 1. 单索引搜索（最常用）

```
GET /api/es/search?index={索引名}&keyword={关键词}&page=1&size=10
```

| 参数 | 必填 | 说明 |
|---|---|---|
| index | 是 | 索引名 / 表名 / **中文名** 都认，忽略大小写和首尾空格 |
| keyword | 否 | 关键词；留空返回全部 |
| page | 否 | 页码，默认 1 |
| size | 否 | 每页条数，默认 20 |

**`index` 参数容错**（手测不用记精确索引名，下面 4 种写法等价）：
```
index=futures_contract_text     ← 索引名
index=FUTURES_CONTRACT_TEXT     ← 大写也认
index=  futures_contract_text   ← 带空格也认
index=期货合约条款               ← 中文名也认（浏览器会自动编码）
```
全部实测通过；写错时返回可读提示 `未知索引: xxx，可用值: [...]`。

**浏览器直接打开这个测：**
```
http://127.0.0.1:8081/api/es/search?index=futures_contract_text&keyword=螺纹钢
http://127.0.0.1:8081/api/es/search?index=futures_contract_text&keyword=保证金
http://127.0.0.1:8081/api/es/search?index=supplier&keyword=供应商
http://127.0.0.1:8081/api/es/search?index=options_contract_text&keyword=行权价
http://127.0.0.1:8081/api/es/search?index=futures_contract&keyword=螺纹钢
```

**实测返回（搜「螺纹钢」）：**
```json
{
  "success": true,
  "code": 200,
  "data": {
    "index": "futures_contract_text",
    "label": "期货合约条款",
    "keyword": "螺纹钢",
    "page": 1, "size": 20, "total": 1, "costMs": 2,
    "records": [{
      "_id": "13", "_score": 11.77,
      "textId": 13, "varietyName": "螺纹钢", "varietyCode": "RB",
      "exchangeName": "郑州商品交易所", "industryName": "黑色金属",
      "tradeUnit": "11吨/手", "quoteUnit": "元/kg",
      "minPriceMove": 4.6, "limitRange": "上一交易日结算价+-4%",
      "contractMonths": "2, 4, 6, 8, 10月", "tradeTime": "上午9:00~11:00",
      "lastTradingDayRule": "合约交割月份的第十二个月",
      "deliveryDateRule": "合约交割月份的第十三个月",
      "minMargin": "合约价值的6%"
    }]
  }
}
```

**终端里用 curl 测（中文务必用 `--data-urlencode`，否则乱码查不到）：**
```bash
curl -s --noproxy '*' -G "http://127.0.0.1:8081/api/es/search" \
  --data-urlencode "index=futures_contract_text" \
  --data-urlencode "keyword=螺纹钢"
```
> Windows 上如果配了系统代理，`--noproxy '*'` 是必须的，否则请求会被代理拦掉。

---

### 2. 多索引聚合搜索（适合全局搜索框）

```
GET /api/es/search/multi?keyword={关键词}&size=5
```

一次查所有索引（或指定 `indexes=a,b`），各索引分别返回结果。

```
http://127.0.0.1:8081/api/es/search/multi?keyword=保证金
http://127.0.0.1:8081/api/es/search/multi?keyword=螺纹钢
http://127.0.0.1:8081/api/es/search/multi?keyword=涨跌停板
# 只查指定索引
http://127.0.0.1:8081/api/es/search/multi?keyword=保证金&indexes=futures_contract_text,options_contract_text
```

---

### 3. 索引清单（给前端做搜索范围下拉）

```
GET /api/es/search/indexes
```

---

## 三、可用索引名（5 个）

| index | 中文 | 可搜字段数 | 典型搜索词 |
|---|---|---|---|
| `futures_contract` | 期货合约 | 4 | 螺纹钢、RB |
| `futures_contract_text` | 期货合约条款 | 12 | 保证金、最后交易日、交易单位、涨跌停板 |
| `options_contract` | 期权合约 | 4 | 期权名、品种 |
| `options_contract_text` | 期权合约条款 | 15 | 行权价、期权类型、到期日 |
| `supplier` | 供应商 | 4 | 供应商名、信用代码、联系电话 |

---

## 四、推荐测试词（全部实测命中 ✅）

| 搜索词 | 命中 | 走哪个字段 |
|---|---|---|
| 螺纹钢 | 期货合约 1 + 条款 1 | `varietyName` |
| 保证金 | 条款 2 | **别名字段**（值里只有"合约价值的6%"） |
| 螺纹钢 保证金 | 条款 1 | **多词跨字段**，验 `cross_fields` |
| 最后交易日 | 条款 2 + 期权条款 2 | 别名 |
| 交易单位 | 条款 2 + 期权条款 2 | 别名 |
| 涨跌停板 | 条款 2 + 期权条款 2 | 别名（字段真名是"涨跌幅度"） |
| 交易所 | 4 个索引共 13 条 | 全索引通吃 |
| 上期所 | 条款 2 | 交易所简称，走别名 |
| 行权价 | 期权条款 2 | 别名 |
| 期权类型 | 期权条款 2 | 别名 |
| 供应商 | 供应商 3 | 名称 |
| 信用代码 | 供应商 3 | 别名（字段真名是 creditCode） |
| 铜 | 期权合约 2 + 期权条款 2 | 品种名 |
| 工业硅 | 条款 1 | 品种名 |
| 徽商 | 供应商 1 | 名称模糊匹配 |

### 库里现在真实有的数据（想搜别的就照这个来）

| 索引 | 实际内容 |
|---|---|
| 期货合约（7 条） | 螺纹钢 RB2610、玉米 C2603/C2609、纯碱 SH2605、烧碱 UR2601、铜2005、氧化钠 等 |
| 期货合约条款（2 条） | 螺纹钢 RB、工业硅 SI |
| 期权合约（2 条） | 铜2004看跌期权 CU2004-P、铜2006看涨期权 CU2005-C |
| 期权合约条款（2 条） | 铜 CU、瓶片 PR |
| 供应商（3 条） | 徽商集团、湛江逸鼎商贸有限公司、新疆贸易有限公司 |

> ⚠️ 搜"豆粕""黄金"这类**库里没有的品种会是 0 条**，这是正常的（不是 ES 故障）。
> 判断 ES 是否正常，看 `success=true` 且 `costMs` 有数值即可。

---

## 五、运维接口（同步 / 排障）

```bash
# 查看各索引文档数 + 重试任务统计
GET  /api/es/status

# 全量同步（首次上线 / 修数据漂移）
POST /api/es/sync/full?recreate=false     # 覆盖写入，不中断服务
POST /api/es/sync/full?recreate=true      # 先删索引再重建，彻底修复

# 只同步单个索引
POST /api/es/sync/index?index=futures_contract_text&recreate=false

# 手动触发补偿（不用等定时任务每分钟跑）
POST /api/es/retry/run

# 重试任务统计
GET  /api/es/retry/stats
```

---

## 六、如何让老接口也走 ES

现在老接口是 MySQL `LIKE`，要改成 ES，最省事的做法是**在 Service 里加开关**：

```java
// 示例：FuturesContractTextServiceImp.findByPage
@Override
public Page<FuturesContractText> findByPage(RequestParam requestParam) {
    if (esProperties.isEnabled() && notBlank(requestParam.getKeyword())) {
        try {
            // 走 ES，命中就返回
            EsDocumentService.SearchResult sr = documentService.search(
                EsIndex.FUTURES_CONTRACT_TEXT, requestParam.getKeyword(),
                requestParam.getPage().intValue(), requestParam.getSize().intValue());
            if (sr.total > 0) {
                return toPage(sr);   // 转成 Page 返回
            }
        } catch (Exception e) {
            log.warn("ES 搜索降级走数据库: {}", e.getMessage());
        }
    }
    // 兜底：原有 MySQL 逻辑
    Page<FuturesContractText> page = new Page(...);
    return futuresContractTextMapper.findByPage(page, requestParam.getKeyword());
}
```

要点：
1. **`if (sr.total > 0)` 才返回 ES 结果** —— 否则空结果会让用户以为"没数据"，而实际上
   MySQL 里可能有（比如 ES 索引还没同步全）。这是"ES 空结果降级"的关键。
2. **`try/catch` 包住 ES 调用** —— ES 挂了自动走数据库，业务不中断。
3. 返回值类型要保持一致（老接口返回 `Page<Entity>`，ES 返回的是 `Map`，需要转换）。

需要我按这个思路把某个接口改掉的话，说一声。

---

## 七、一键验收（不想手点就跑这个）

项目根目录已有 `verify-es.sh`，默认打 8081，也可以传端口：

```bash
cd /d/apesource/SmartHedge_Risk_Hub/SmartHedge_Risk_Hub
bash verify-es.sh            # 打 8081
bash verify-es.sh 8080       # 打你自己的实例
```

它会跑 14 条断言：索引数据量、中文命中、多词跨字段、别名字段、双写增删一致性、
重试表积压数，最后打印 `PASS=x FAIL=y`。全绿说明 ES 链路完整可用。
