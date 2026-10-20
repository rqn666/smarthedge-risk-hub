# ES 搜索 —— 逐行白话讲解

> 写给"知道 ES 是搜索引擎、但没自己搭过一整套"的自己。  
> 目标：**能讲清楚每一行为什么这么写**，不是背术语。  
> 本文所有数字均为本机实测（ES 7.16.2，2026-09-24）。

---

## 0. 先说结论：这一套一共 15 个文件，但要懂的只有 5 个

| 文件                               | 干什么                       | 大概几行            |
| -------------------------------- | ------------------------- | --------------- |
| `es/sync/EsWriteHelper.java`     | **双写大门**：决定"什么时候"往 ES 写   | 有效代码 20 行       |
| `es/sync/EsSyncService.java`     | **双写本体**：把数据推给 ES，失败就记小本本 | 有效代码 25 行       |
| `es/sync/EsRetryScheduler.java`  | **补偿工**：小本本上没干完的活，定时重做    | 有效代码 40 行       |
| `es/core/EsDocumentService.java` | **搜索核心**：拼查询条件、查 ES、算耗时   | 搜索方法 50 行       |
| `es/constant/EsIndex.java`       | **字典**：5 张表各自有哪些字段、中文名叫啥  | 332 行（都是配置，不用背） |

剩下的是：建索引 `EsIndexManager`、启动时自检 `EsIndexInitializer`、实体转文档 `EsDocConverter`、  
文档载体 `EsDoc`、两个 Controller（搜索 / 运维）、客户端配置 `EsConfig`、参数 `EsProperties`。

**用的时候业务代码只加一行：**

```java
int rows = futuresContractTextMapper.insertText(futuresContractText);   // 正常写 MySQL
if (rows > 0) {
    esWriteHelper.upsertAfterCommit(EsIndex.FUTURES_CONTRACT_TEXT, futuresContractText);  // 就这行
}
```

业务方法里**没有任何 ES 的代码**，数据自动就进了 ES。这就是整套东西的价值。

---

## 1. 到底为什么要上 ES？（先讲清楚痛点，不然面试说不清动机）

原来搜索是这么写的：

```sql
select * from futures_contract_text where min_margin like '%保证金%'
```

**两个致命毛病：**

**① `%` 打头用不上索引**  
MySQL 的索引像字典的拼音目录，得从第一个字开始查。`'%保证金%'` 是"中间包含"，  
目录没法用，只能**一行一行翻完整本书**（全表扫描）。数据一多就慢。

**② 只能机械匹配字眼，不理解意思**  
用户搜"保证金"，数据库里那条记录的值是 `合约价值的6%` —— **压根没有"保证金"这三个字**。  
`like` 查出来 0 条，用户以为系统坏了。

**ES 干的事，本质就一句话：提前把书翻一遍，做成"词语 → 出现在第几页"的目录。**

```
"螺纹钢"  → 出现在 第13条、第7条
"保证金"  → 出现在 第1、13、24、25条
"上期所"  → 出现在 …
```

这个目录叫**倒排索引**。查的时候不用翻书，直接查目录，所以快。

再加一件 MySQL 做不到的事：**中文分词**。  
"郑州商品交易所"会被切成 `郑州 / 商品 / 交易所 / 郑州商品交易所` 好几个词，  
用户只输"交易所"也能命中。MySQL 的 `like` 干不了这个。

---


## 2. 数据是怎么进 ES 的？（三条路，一张图看明白）

```
                        ┌─────────────────────────────────┐
                        │       MySQL（真正的数据源）        │
                        └─────────────────────────────────┘
                                        │
          ┌─────────────────────────────┼─────────────────────────────┐
          │                             │                             │
      【路① 日常双写】              【路② 全量同步】              【路③ 失败补偿】
   每笔业务写完后            首次上线 / 数据乱了          路① 失败时补做
   实时同步一条              手动触发，整表灌一遍          定时任务每分钟扫一次
          │                             │                             │
   业务 Service 调               POST /api/es/sync/full       @Scheduled 扫 es_retry_task
   upsertAfterCommit            500 条一批 bulk 写入          反查 MySQL 最新数据重建
          │                             │                             │
          ▼                             ▼                             ▼
    成功 → ES ✓                    成功 → ES ✓                   成功 → 标记 SUCCESS
    失败 → 记进重试表                                             失败 → 次数+1
          │                                                     超 5 次 → 标记 DEAD
          └─────────────────────────────┘
                                        │
                                        ▼
                        ┌─────────────────────────────────┐
                        │   Elasticsearch（加速层，不是数据源）│
                        └─────────────────────────────────┘
```

**一句话记住**：路① 管日常，路② 管初始化和兜底修复，路③ 管路① 的烂摊子。  
**ES 挂了不影响业务** —— 因为数据始终以 MySQL 为准，ES 只是个"加速缓存"。

---

## 3. 路① 双写：核心 20 行（唯一要背的）

### 3.1 大门：什么时候写？`EsWriteHelper.java`

```java
public void upsertAfterCommit(final EsIndex index, final Object entity) {
    // ① 问一句：现在是不是在数据库事务里？
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        // ② 在事务里 → 不马上写，先"挂个号"：等事务真的提交成功了再叫我
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    syncService.upsert(index, entity);   // ③ 事务提交后，这里才真正写 ES
                } catch (Exception e) {
                    log.error("[es] afterCommit 双写异常: {}", e.getMessage());
                }
            }
        });
    } else {
        // ④ 不在事务里 → 没什么好等的，直接写
        syncService.upsert(index, entity);
    }
}
```

### 逐行白话

| 行                              | 白话解释                           |
| ------------------------------ | ------------------------------ |
| `isSynchronizationActive()`    | "我现在是不是被包在一个数据库事务里？"           |
| `registerSynchronization(...)` | "那我排个队，等事务**成功提交**的那一刻通知我"     |
| `afterCommit()`                | **事务提交成功后**才会触发的回调。事务回滚了就永远不触发 |
| `syncService.upsert(...)`      | 真正去写 ES                        |
| 外面的 `try-catch`                | 写 ES 失败也不能让事务框架报错，打日志完事        |

### ⭐ 为什么要绕这么一圈？（面试必问，答对了很加分）

**因为事务会回滚。**

想象这个场景：`insertText` 方法标了 `@Transactional`，里面先写 MySQL，再写 ES，最后因为某个校验失败  
整个事务回滚了。

- **如果直接写 ES**：MySQL 回滚了（数据没了），但 ES 已经写进去了（数据还在）→ 用户搜得到，点进去  
  发现"数据不存在"。这就是**脏数据**，而且非常难排查。
- **用 afterCommit**：事务没成功提交，回调根本不触发，ES 里压根不会有这条。

**一句话答法**：

> 双写必须等 MySQL 事务提交成功之后再做，否则事务一回滚就会出现"ES 有、库里没有"的幽灵数据。



---

### 3.2 本体：怎么写？`EsSyncService.upsert()`

```java
public void upsert(EsIndex index, Object entity) {
    if (!props.isEnabled()) {          // ① 总开关关了 → 直接跳过
        return;
    }
    try {
        EsDoc doc = docConverter.fromEntity(index, entity);   // ② 业务对象 → ES 文档
        if (doc == null) {
            log.warn("[es] 转换文档为空，跳过双写");
            return;
        }
        documentService.save(index, doc);                     // ③ 写进 ES
    } catch (Exception e) {
        log.warn("[es] 双写失败: {}", e.getMessage());
        recordRetry(index, extractId(index, entity), "UPSERT", e.getMessage());  // ④ 记小本本
    }
}
```

| 行                              | 白话解释                                                     |
| ------------------------------ | -------------------------------------------------------- |
| `props.isEnabled()`            | 总开关。`application.yml` 里 `es.enabled: false` 就整个跳过，排障/回滚用 |
| `docConverter.fromEntity(...)` | 把 Java 对象翻译成 ES 认识的格式（下面第 5 节细说）                         |
| `documentService.save(...)`    | 发 HTTP 请求给 ES 写入                                         |
| `catch` 里 `recordRetry(...)`   | **写失败不抛异常**，往 `es_retry_task` 表插一条记录，交给路③ 去补             |

**这就是"不阻塞主业务"的全部秘密**：整段 try-catch，ES 炸了也只是打一行 warn 日志，  
业务接口照常返回成功。

---

## 4. 路③ 补偿：失败了怎么补？（这块最能体现设计水平）

定时任务每分钟扫一次待办（`es_retry_task` 表里 `status='PENDING'` 的记录）：

```java
private boolean replay(EsRetryTask task) {
    // ① 删除类型的失败 → 直接再删一次（删不存在的文档也不报错，幂等）
    if ("DELETE".equalsIgnoreCase(task.getOpType())) {
        documentService.deleteById(index, task.getBizId());
        syncService.markSuccess(task.getId());
        return true;
    }

    // ② 新增/修改类型的失败 → 从 MySQL 反查这条数据的最新样子
    EsDoc doc = fullSyncService.loadOne(index, task.getBizId());

    if (doc == null || doc.isEmpty()) {
        // ③ 库里已经没这条数据了 → 说明这段时间被删了 → 把 ES 里的也删掉（自愈）
        documentService.deleteById(index, task.getBizId());
        syncService.markSuccess(task.getId());
        return true;
    }

    documentService.save(index, doc);          // ④ 重建文档写进去
    syncService.markSuccess(task.getId());     // ⑤ 标记成功
    return true;
}
```

### 两个关键设计（面试讲出来很值钱）

**① 为什么"反查 MySQL"而不是"重放之前那份数据"？**

因为失败到补偿之间可能过了几十秒，这期间那条数据**可能又被改过**。  
如果重放旧数据，就会把过期的版本写回 ES，覆盖掉新版本。  
所以必须回源查一次"现在最新长什么样"。

**② 为什么查不到就删 ES 文档？**

查不到只有一种可能：业务上已经把这条数据删了。那 ES 里还留着就是脏数据 → 顺势删掉。  
这叫**自愈**，不用人工介入。

**③ 重试次数用完了怎么办？**  
`retry_count` 超过 `max_retry`（默认 5 次）就标记 `DEAD`，不再自动重试，等人工处理。  
避免一条永远失败的任务把定时任务卡死。

---

## 5. ES 里那条数据长什么样？（`EsDoc` + 别名 `__alias`）

为什么不直接把 Java 对象丢给 ES？因为对象里有一堆 ES 不需要的东西（关联对象、多余字段），  
直接序列化会污染索引。所以用一个 `Map` 手动挑字段。

**实际存在 ES 里的一条记录（实测 `_id=13`）：**

```json
{
  "textId": 13,
  "contractId": 18,
  "varietyId": 14,
  "varietyName": "螺纹钢",
  "varietyCode": "RB",
  "exchangeId": 2,
  "exchangeName": "郑州商品交易所",
  "industryName": "黑色金属",
  "tradeUnit": "11吨/手",
  "quoteUnit": "元/kg",
  "minPriceMove": 4.6,
  "minMargin": "合约价值的6%",
  "limitRange": "上一交易日结算价+-4%",
  "contractMonths": "2, 4, 6, 8, 10月",
  "__alias": "tradeUnit 交易单位 合约单位 一手多少吨 quoteUnit 报价单位 计价单位
              minPriceMove 最小变动价位 … minMargin 保证金 最低保证金 保证金比例
              保证金率 exchangeName 交易所 上期所 大商所 郑商所 …"
}
```

### ⭐ `__alias` 到底是什么？（看不懂就看这一节）

**先纠正最容易误解的一点：`__alias` 不是数据库字段，MySQL 里没有这一列，Java 实体里也没有这个属性。**

它是**代码在写 ES 文档时人为塞进去的一个"假字段"**。全项目只在两处出现：
建索引时声明它的类型（`EsIndex.java`）、写文档时往里填内容（`EsDocConverter.java`）。

#### 为什么非要造这么一个字段？

因为用户会搜"保证金"，而**"保证金"这三个中文字，在整个系统里原本一次都没出现过**：

| 位置 | 长什么样 |
|------|---------|
| MySQL 列名 | `min_margin` |
| Java 字段名 | `minMargin` |
| 字段的值 | `合约价值的6%` |
| 前端页面的 label | 保证金 ← 只有这里出现过 |

而 ES 有条硬规则：**只能搜"字段的值"，不能搜"字段的名字"**。  
所以用户输"保证金" → ES 拿这个词去翻每条文档的每个字段的值 → 值里全是"合约价值的6%"这种 → **一条都搜不到（零召回）**。

打个比方：ES 文档像一张名片，有格子名（`minMargin`）和格子里的内容（`合约价值的6%`）。  
ES 只在**格子内容**里找字，从来不认**格子上的标题**；  
而这个格子的中文标题"保证金"，压根也没写在名片上。

**解法**：在名片背面手抄一份"栏目名清单"，把每个字段的中文名、俗称全写一遍 —— 这份清单就是 `__alias`。

#### 三段代码，就这三步

**第 1 步：声明"每个字段有哪些中文说法"** —— `EsIndex.java` 建索引时配置：

```java
minMargin=保证金 最低保证金 保证金比例 保证金率
exchangeName=交易所 上期所 大商所 郑商所 中金所 广期所 ...
```

`=` 左边是字段名，右边是"所有可能被搜到的中文说法"，用空格隔开。

**第 2 步：把所有声明拼成一段长文本** —— `EsIndex.buildAliasText()`：

```java
for (Map.Entry<String, String> e : aliases.entrySet()) {
    // "minMargin=保证金 最低保证金" -> 等号左边的英文名也拼进去，让 "minMargin" 本身可搜
    sb.append(e.getKey()).append(' ').append(e.getValue()).append(' ');
}
```

拼出来就是这 350 个字（下面是**实测的真实内容**）：

```
tradeUnit 交易单位 合约单位 一手多少吨 quoteUnit 报价单位 计价单位
minPriceMove 最小变动价位 最小变动单位 一跳 limitRange 涨跌停板 涨跌幅限制 停板幅度
contractMonths 合约月份 交割月份 挂牌月份 tradeTime 交易时间 交易时段 开盘时间
lastTradingDayRule 最后交易日 最后交易日规则 deliveryDateRule 交割日期 交割规则
minMargin 保证金 最低保证金 保证金比例 保证金率
exchangeName 交易所 上期所 大商所 郑商所 中金所 广期所 上海期货交易所 ...
varietyName 品种 商品 合约品种
```

**第 3 步：写文档时把它塞进去** —— `EsDocConverter.withAlias()`：

```java
String aliasText = index.buildAliasText();
if (!aliasText.isEmpty()) {
    doc.put(EsIndex.ALIAS_FIELD, aliasText);   // ALIAS_FIELD 就是 "__alias"
}
```

#### ⚠️ 代价：这段文本跟"这条数据"没有半点关系

注意第 3 步里 `buildAliasText()` 收的参数是 **`index`（索引定义）**，**不是 `doc`（这条数据）**。  
也就是说：

> 不管这条记录是螺纹钢还是尿素，`__alias` 塞进去的都是**同一段 350 字文本**。

实测证据（对 4 条文档的 `__alias` 取 md5）:

| 文档 `_id`  | 品种  | 保证金       | `__alias` 长度 | md5          |
| --------- | --- | --------- | ------------ | ------------ |
| 1         | 工业硅 | 合约价值的5%   | 350          | `265b064c1d07` |
| 13        | 螺纹钢 | 合约价值的6%   | 350          | `265b064c1d07` |
| 24        | 尿素  | 合约价值的5%   | 350          | `265b064c1d07` |
| 25        | 瓶片  | 合约价值的5%   | 350          | `265b064c1d07` |

四条一模一样。后果随之而来：

| 搜索词       | 命中          | 实测得分    | 说明                                           |
| --------- | ----------- | ------- | -------------------------------------------- |
| `螺纹钢`     | **1 条**     | 13.81   | ✅ 精准命中，靠 `varietyName`                       |
| `工业硅`     | **1 条**     | 13.81   | ✅ 精准                                         |
| `保证金`     | **4 条（全部）** | 全是 0.89 | ⚠️ 每条文档的 `__alias` 都一样，全都命中且**得分完全相同**，排不出先后 |
| `上期所`     | **4 条（全部）** | 全是 1.19 | ⚠️ 同上                                        |
| `合约品种`    | **4 条（全部）** | 全是 1.19 | ⚠️ 泛词同样全中                                      |
| `螺纹钢 保证金` | **1 条**     | 3.68    | ✅ 第二个词把范围收敛了                                 |

一句话总结：**`__alias` 是"用存储换召回"，代价是丢掉了"字段元数据"和"具体某条数据"之间的对应关系。**

#### 怎么修？（被问"下一步怎么优化"时答这个）

1. **让它跟着数据走**：`buildAliasText()` 改成接收当前 `doc`，只拼**这条数据里该字段非空**的别名  
   → 没有 `minMargin` 的记录，就不会被"保证金"命中。这是最直接的修法。
2. **降权**：现在 `__alias` 权重 1.5（`EsDocumentService.weightOf()`），比普通正文字段的 1.0 还高，  
   可以降到 0.3~0.5，让它只负责兜底、不参与抢排名。
3. **前端引导**：把"保证金"这类字段名做成筛选项（`exists` 查询），别让用户拿字段名当关键词搜。

#### 被追问"搜保证金全命中，你这不就等于没搜吗"怎么答？

> 对，这是已知短板。`__alias` 解决的是"零召回"问题 —— 用户搜"保证金"不至于一条都出不来；  
> 代价是泛词会把整个索引都捞出来，因为它的内容是**按索引整体拼的**，和具体哪条数据无关，同索引下每条都一模一样。  
> 真正的排序靠**多词组合**：用户实际会搜"螺纹钢 保证金"，  
> 这时 `cross_fields + AND` 要求两个词都命中，结果就收敛成 1 条了（实测 4 条 → 1 条）。  
> 更彻底的做法是让别名跟着数据走 —— 只拼该条数据非空字段的别名，再把它降权到 0.3 左右。

**把"已知短板 + 下一步计划"讲出来，比假装完美更能拿分。**

---


## 6. 搜索核心：`EsDocumentService.search()` 逐行

```java
public SearchResult search(EsIndex index, String keyword, int page, int size) {
    Map<String, Float> fieldsWithBoost = buildFieldBoosts(index);   // ① 给字段配权重
    BoolQueryBuilder bool = QueryBuilders.boolQuery();

    if (keyword != null && !keyword.trim().isEmpty()) {
        // ② 主查询：cross_fields + AND
        bool.should(QueryBuilders.multiMatchQuery(kw)
                .fields(fieldsWithBoost)
                .operator(Operator.AND)
                .type(MultiMatchQueryBuilder.Type.CROSS_FIELDS));

        // ③ 辅助查询：完整短语命中，权重 ×3
        bool.should(QueryBuilders.multiMatchQuery(kw)
                .fields(fieldsWithBoost)
                .type(MultiMatchQueryBuilder.Type.PHRASE)
                .boost(3.0f));

        bool.minimumShouldMatch(1);
    } else {
        bool.must(QueryBuilders.matchAllQuery());   // ④ 没关键词 → 全查
    }
    // ⑤ 翻页 + 按相关度排序
    SearchSourceBuilder source = new SearchSourceBuilder()
            .query(bool)
            .from((page - 1) * size)
            .size(size)
            .trackTotalHits(true)
            .sort(SortBuilders.scoreSort().order(SortOrder.DESC));
    // …发请求、取结果、算耗时
}
```

### 逐行白话

| 行                       | 白话解释                                                                  |
| ----------------------- | --------------------------------------------------------------------- |
| `buildFieldBoosts(...)` | 给字段配"重要程度"：品种名/合约名 **3 倍**，代码/交易所名 **2 倍**，`__alias` **1.5 倍**，正文 1 倍 |
| `CROSS_FIELDS`          | **关键**：允许"螺纹钢"在品种名字段、"保证金"在别名字段，**跨字段拼起来满足** AND                      |
| `operator(AND)`         | 两个词都得有，只命中一个的不算                                                       |
| `PHRASE + boost(3.0)`   | 如果用户输入的是**连着的一整句话**，说明更精确，得分 ×3 排前面                                   |
| `minimumShouldMatch(1)` | 两条查询至少中一条就算命中                                                         |
| `scoreSort().DESC()`    | 按相关度得分从高到低排                                                           |
| `trackTotalHits(true)`  | 要准确的总条数（ES 7 之后默认只数到 1 万就不数了）                                         |

### ⭐ 为什么用 `cross_fields` 不用默认的 `best_fields`？（踩过的坑）

默认的 `best_fields` 要求**同一个字段**同时命中所有词。

搜"螺纹钢 保证金"时：

- "螺纹钢" 在 `varietyName` 字段
- "保证金" 在 `__alias` 字段

没有任何单个字段同时包含这两个词 → **`best_fields` 返回 0 条**。这是实测踩出来的。

`cross_fields` 把查询词拆开，允许跨字段组合满足 AND，才符合用户预期。

---

## 7. 降级设计：ES 挂了会怎么样？（四条保险）

| 保险            | 怎么做的                                   | 在哪                        |
| ------------- | -------------------------------------- | ------------------------- |
| **启动不阻塞**     | 启动时建索引，ES 连不上**只打日志不抛异常**，应用照常起来       | `EsIndexInitializer.java` |
| **总开关**       | `es.enabled: false` 一键全部跳过 ES，业务走数据库   | `application.yml`         |
| **搜索失败返 503** | 不返 500，而是返 503 + "请降级走数据库查询"，明确告诉调用方回退 | `EsSearchController.java` |
| **双写失败不抛**    | 全部 try-catch + 落重试表，主业务零影响             | `EsSyncService.java`      |

**实测过的降级链路**：把 ES 停掉 → 业务写入照样成功、任务进 `es_retry_task` 表（PENDING）→  
重启 ES → 调一次 `POST /api/es/retry/run` → 任务变 SUCCESS，数据补进 ES。

**一句话定位**：ES 是**加速层，不是数据源**。它挂了，业务慢一点（走数据库），但不会错、不会停。

---

## 8. 30 秒话术（照着念）

> 我这个项目原来用 MySQL 的 `like '%关键词%'` 做搜索，两个问题：  
> 一是百分号打头用不上索引、数据一多就全表扫描；二是只能机械匹配字眼，  
> 用户搜"保证金"而库里存的是"合约价值的6%"，直接返回 0 条。
>
> 所以我引入了 Elasticsearch 7.16 加 IK 中文分词，把数据做成倒排索引。  
> 数据同步上做了三件事：日常走**事务提交后的双写** —— 必须等 MySQL 事务提交成功才写 ES，  
> 否则事务一回滚就会出现"ES 有、库里没有"的脏数据；写失败落重试表，由定时任务补偿，  
> 补偿时**回源 MySQL 反查最新数据**而不是重放旧文档，查不到就顺手删掉 ES 文档实现自愈；  
> 另外提供全量同步接口用于首次初始化和兜底修复。
>
> 搜索侧用 `cross_fields` 解决多词跨字段零召回的问题，对品种名等业务字段配置 1.5 到 3 倍权重，  
> 再叠加短语查询提权。ES 不可用时搜索接口返回 503 引导调用方回退数据库，业务写入完全不受影响。  
> 实测精准查询 3~12 毫秒。

---

## 9. 追问准备

| 追问                                     | 答法                                                                                                     |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| **为什么不在事务里直接写 ES？**                    | 事务可能回滚。MySQL 回滚了 ES 已写入 → 幽灵数据。所以注册 `afterCommit` 回调，提交成功才写。                                           |
| **ES 和 MySQL 数据不一致怎么办？**               | 短期不一致由重试表兜底；长期有全量同步接口可以整表重建。数据始终以 MySQL 为准。                                                            |
| **为什么不用 Canal / MQ 同步？**               | 单体项目、链路短，双写够用且可控。真到了多下游、异构消费的场景再上 Canal + MQ，现在属于过度设计。                                                 |
| **补偿为什么反查 MySQL？**                     | 失败到补偿之间数据可能又被改过，重放旧文档会把过期版本写回 ES。                                                                      |
| **`cross_fields` 和 `best_fields` 区别？** | best_fields 要求同一字段命中所有词，搜"螺纹钢 保证金"会返回 0 条；cross_fields 允许跨字段组合。实测踩过。                                   |
| **为什么建索引用 ik_max_word、搜索用 ik_smart？**  | 建索引时切得越细召回越全（"郑州商品交易所"切成好几个词）；搜索时切得粗一点更精准。                                                             |
| **文档 `_id` 用什么？**                      | 直接用业务表主键。重复写就是覆盖，天然幂等，不用担心重复数据。                                                                        |
| **分词器怎么装的？**                           | IK 插件放进 ES 的 plugins 目录，重启生效。mapping 里给 text 字段指定 `analyzer: ik_max_word`、`search_analyzer: ik_smart`。 |
| **搜"保证金"把整个索引都捞出来了，这不算 bug 吗？**        | 见第 5 节 —— 如实承认是已知短板，讲清楚 `__alias` 的取舍和多词收敛的效果。                                                         |
| **bulk 写完为什么要 refresh？**               | ES 默认 1 秒才刷新一次可搜索段，写完立刻 count 会读到旧值，导致一致性校验误报。全量同步末尾显式 refresh。注意别在高频写路径调用，有性能开销。                      |

---

## 10. 怎么自己验证（可复跑）

ES 需要先启动：`D:\ape\zhongjianjian2\elasticsearch-7.16.2\bin\elasticsearch.bat`

```bash
# 1. ES 活着吗？看看 5 个索引各有多少条数据
curl "http://127.0.0.1:9200/_cat/indices?v"

# 2. 看一条真实文档（确认 __alias 长啥样）
curl "http://127.0.0.1:9200/futures_contract_text/_search?size=1&pretty"

# 3. 搜精准词（应命中 1 条，score 13.81）
curl "http://127.0.0.1:9200/futures_contract_text/_search?pretty" -H "Content-Type: application/json" \
  -d '{"query":{"multi_match":{"query":"螺纹钢","fields":["varietyName^3.0","__alias^1.5"],"type":"cross_fields","operator":"and"}}}'

# 4. 验证 __alias 是"每篇一模一样"的（期望输出 1：四篇文档只有一种 __alias）
curl -s "http://127.0.0.1:9200/futures_contract_text/_search?size=20&_source=__alias" \
  | grep -o '"__alias":"[^"]*"' | sort -u | wc -l

# 5. 验证泛词会全命中（搜"上期所"返回 4 条、score 全为 1.19 → 这是已知短板，不是 bug）
curl "http://127.0.0.1:9200/futures_contract_text/_search?pretty" -H "Content-Type: application/json" \
  -d '{"query":{"multi_match":{"query":"上期所","fields":["varietyName^3.0","__alias^1.5"],"type":"cross_fields","operator":"and"}}}'

# 6. 应用起来后（端口 18081）
curl "http://127.0.0.1:18081/api/es/search?index=futures_contract_text&keyword=%E8%9E%BA%E7%BA%B9%E9%92%A2"  # 螺纹钢
curl "http://127.0.0.1:18081/api/es/status"          # 各索引文档数 + 重试任务统计
curl -X POST "http://127.0.0.1:18081/api/es/retry/run"   # 手动触发一次补偿
curl -X POST "http://127.0.0.1:18081/api/es/sync/full"   # 全量同步
```

> 更多接口见 `docs/ES接口测试清单.md`。

---

## 11. 一句话记住整套东西

> **MySQL 是账本，ES 是目录。**  
> 账本写成功了（事务提交），才去更新目录；目录更新失败先记在小本本上，回头补；  
> 补的时候重新看一眼账本现在长什么样，别拿旧纸条糊上去；目录没了也不影响记账，只是查得慢一点。
