# Elasticsearch 搜索功能 · 落地文档

> 项目：SmartHedge_Risk_Hub（大宗商品套保风控）
> 日期：2026-09-22
> 状态：已落地并通过端到端验收（14/14 断言）

---

## 1. 需求与决策

| 项 | 结论 |
|---|---|
| 布隆过滤器 | **不采用**。搜索场景下它只能回答"可能存在/一定不存在"，精度不可控，且 ES 本身已是加速层，加了反而多一层维护成本。 |
| ES 版本 | 7.16.2（与服务端严格一致，客户端版本不一致会被 ES 拒绝） |
| 分词器 | analysis-ik 7.16.2；建索引用 `ik_max_word`（召回全），搜索用 `ik_smart`（精度高） |
| 数据一致性 | **代码双写**：MySQL 写成功后同步写 ES；失败落重试表，定时任务补偿 |
| 写 ES 时机 | **事务 afterCommit 之后**，避免 MySQL 回滚而 ES 已写入的脏数据 |
| 历史数据 | 提供全量同步接口，手动触发一次 |

---

## 2. 架构

```
业务写请求
    │
    ▼
┌─────────────────┐
│  Service 写 MySQL │ ← 事务边界
└────────┬────────┘
         │ MySQL 提交成功
         ▼
┌──────────────────────┐
│ EsWriteHelper         │  判断是否在事务中
│  · 事务中 → afterCommit │
│  · 不在事务 → 立即执行   │
└────────┬─────────────┘
         ▼
┌──────────────────────┐       失败      ┌──────────────────┐
│ EsSyncService.upsert │ ─────────────▶ │  es_retry_task    │
│ EsDocConverter       │                │  status=PENDING   │
│ EsDocumentService    │                └────────┬─────────┘
└────────┬─────────────┘                         │
         ▼                                       ▼
    Elasticsearch                    ┌────────────────────────┐
    （5 个索引，IK 分词）              │ EsRetryScheduler        │
                                     │ 每分钟扫 PENDING        │
                                     │ 从 MySQL 反查重建文档    │
                                     │ 超 max_retry → DEAD     │
                                     └────────────────────────┘
```

**为什么补偿时是从 MySQL 反查而不是重放原文档？**
失败后数据可能已被再次修改，反查能拿到"当前最新版本"，避免把旧版本写回 ES。

**为什么 ES 挂了业务还能跑？**
ES 在本项目里是加速层，不是数据源。`EsIndexInitializer` 启动失败只打日志不抛异常；
`EsSyncService` 吞掉所有 ES 异常。搜索接口在 ES 不可用时返回 503 + 提示，调用方降级走数据库。

---

## 3. 索引设计

| 索引 | 表 | 主键 | text 字段数 | 中文别名 |
|---|---|---|---|---|
| `futures_contract` | futures_contract | contractId | 5 | - |
| `futures_contract_text` | futures_contract_text | textId | 15 | ✓ |
| `options_contract` | options_contract | optionId | 5 | - |
| `options_contract_text` | options_contract_text | textId | 18 | ✓ |
| `supplier` | supplier | supplierId | 5 | ✓ |

### 三个关键设计

**① 冗余维度字段**
索引里存了 `varietyName` / `exchangeName` / `industryName` / `operatorName`，
这些在 MySQL 里是 join 出来的。不冗余进来，用户搜"螺纹钢""上期所"就搜不到。
全量同步的 SQL 用 `left join` 补齐（见 `EsFullSyncService.selectSql`）。

**② `__alias` 别名字段 —— 解决"字段名不可搜"**
ES 只能搜字段**值**，搜不到字段**名**。而用户问的是"保证金多少"，
字段值却是"合约价值的6%"——字面上不含"保证金"三个字，搜不到。

方案：把字段中文名 + 同义词拼成一段文本写进 `__alias` 字段参与检索。

```java
aliases(
    "minMargin=保证金 最低保证金 保证金比例 保证金率",
    "lastTradingDayRule=最后交易日 最后交易日规则",
    "exchangeName=交易所 上期所 大商所 郑商所 中金所 广期所",
    ...
)
```
- 权重 1.5（低于名称类，高于普通正文）
- 返回结果时从 `_source` 剔除，不暴露给前端

**③ 搜索用 `cross_fields` 而非 `best_fields`**
`best_fields` 要求**同一个字段**同时命中查询的所有词。
搜"螺纹钢 保证金"时，"螺纹钢"在 `varietyName`、"保证金"在 `__alias`，
没有单个字段同时含两词 → 返回 0 条（实测踩过这个坑）。
`cross_fields` 按词拆分、允许跨字段满足 AND，才是用户期望的语义。

### 字段权重
| 字段类型 | 权重 |
|---|---|
| 名称类（varietyName/contractName/optionName/supplierName） | 3.0 |
| 编码类（varietyCode/contractCode/exchangeName/industryName） | 2.0 |
| 别名聚合 `__alias` | 1.5 |
| 普通正文 | 1.0 |

---

## 4. 接口清单

### 搜索（`/api/es/search`）
```bash
# 单索引搜索
GET /api/es/search?index=futures_contract_text&keyword=螺纹钢&page=1&size=10

# 多索引聚合（各索引分别返回，适合全局搜索框）
GET /api/es/search/multi?keyword=保证金&size=5

# 可用索引列表（给前端做搜索范围下拉）
GET /api/es/search/indexes
```

### 运维（`/api/es`）
```bash
# 全量同步（recreate=true 先删索引重建，用于彻底修复）
POST /api/es/sync/full?recreate=false

# 单索引同步
POST /api/es/sync/index?index=futures_contract_text&recreate=false

# 手动触发补偿（不用等定时任务）
POST /api/es/retry/run

# 查看各索引文档数 + 重试任务统计
GET /api/es/status
GET /api/es/retry/stats
```

> ⚠️ 运维接口会做重活，线上必须加管理员鉴权。等 Shiro 模块接入后把这些路径纳入拦截。

---

## 5. 配置（application.yml）

```yaml
es:
  hosts: 127.0.0.1:9200
  username: ""            # ES 未开安全认证留空
  password: ""
  connect-timeout-ms: 5000
  socket-timeout-ms: 30000
  default-size: 20
  enabled: true           # 总开关，false 时全部跳过 ES（排障/回滚用）
  retry-enabled: true
  retry-fixed-delay-ms: 60000
  retry-max-times: 5
```

---

## 6. 数据库变更

执行一次：`src/main/resources/sql/es_retry_task.sql`

```sql
CREATE TABLE IF NOT EXISTS `es_retry_task` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `index_name`  VARCHAR(64)  NOT NULL COMMENT '目标 ES 索引名',
  `table_name`  VARCHAR(64)  NOT NULL COMMENT '对应 MySQL 表名',
  `biz_id`      VARCHAR(64)  NOT NULL COMMENT '业务主键（同时是 ES 文档 _id）',
  `op_type`     VARCHAR(16)  NOT NULL COMMENT 'UPSERT / DELETE',
  `retry_count` INT          NOT NULL DEFAULT 0,
  `max_retry`   INT          NOT NULL DEFAULT 5,
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  `error_msg`   VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_index_biz` (`index_name`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ES 双写失败重试任务';
```

---

## 7. 首次上线步骤

```bash
# 1. 建重试表
mysql -h127.0.0.1 -P3306 -uroot -p<密码> smarthedge_risk_hub < src/main/resources/sql/es_retry_task.sql

# 2. 启动应用（会自动创建 5 个索引）
mvn spring-boot:run

# 3. 手动触发一次全量同步
curl -X POST "http://127.0.0.1:8081/api/es/sync/full?recreate=false"

# 4. 验收（14 条断言）
bash verify-es.sh
```

---

## 8. 排障手册

| 现象 | 原因 | 处理 |
|---|---|---|
| 搜中文词返回 0 条 | 该词既不在字段值里也不在别名里 | 在 `EsIndex.aliases()` 补别名后重建索引 |
| `consistent=false` | count 早于 ES refresh | 已在内置 refresh；若仍出现看是否写失败 |
| 新增后 ES 里 `_id=0` | Mapper 的 `@Insert` 缺 `useGeneratedKeys` | 补 `@Options(useGeneratedKeys=true, keyProperty=..., keyColumn=...)` |
| 删除后 ES 还有数据 | ES 文档 `_id` 与业务主键不一致 | 检查主键回填；`POST /api/es/retry/run` 会自愈 |
| 重试表堆积 `DEAD` | 反复失败超 max_retry | `SELECT * FROM es_retry_task WHERE status='DEAD'`；修因后重置为 PENDING |
| 索引字段类型冲突 | mapping 与写入数据类型不符 | `POST /api/es/sync/index?index=xxx&recreate=true` 重建 |

### 常用 SQL
```sql
-- 待重试
SELECT * FROM es_retry_task WHERE status='PENDING' ORDER BY id;
-- 需人工介入
SELECT * FROM es_retry_task WHERE status='DEAD' ORDER BY update_time DESC;
-- 各状态统计
SELECT status, COUNT(*) FROM es_retry_task GROUP BY status;
-- 重置让某条重试
UPDATE es_retry_task SET status='PENDING', retry_count=0, error_msg=NULL WHERE id=?;
```

---

## 9. 验收结果（2026-09-22 实测）

```
A. 全量同步一致性
  [PASS] futures_contract         MySQL=  7 ES=  7
  [PASS] futures_contract_text    MySQL=  2 ES=  2
  [PASS] options_contract         MySQL=  2 ES=  2
  [PASS] options_contract_text    MySQL=  2 ES=  2
  [PASS] supplier                 MySQL=  3 ES=  3

B. 中文搜索命中
  [PASS] 螺纹钢 2 条    [PASS] 保证金 2 条      [PASS] 最后交易日 4 条
  [PASS] 交易单位 4 条  [PASS] 涨跌停板 4 条    [PASS] 行权价 2 条
  [PASS] 期权类型 2 条  [PASS] 供应商 3 条

C. 双写链路
  [PASS] 新增后 MySQL 2→3、ES 同步 2→3
  [PASS] ES 按 _id 能查到（主键回填正确）
  [PASS] 删除后 MySQL/ES 双双回到 2
  [PASS] 重试表无 PENDING 积压

结果: PASS=14  FAIL=0
```

---

## 10. 简历话术（可直接用）

> **Elasticsearch 全文检索与双写一致性**
> 为大宗商品风控系统集成 ES 7.16 + IK 分词，覆盖期货/期权合约及条款、供应商 5 张核心表。
> 采用代码双写保证 MySQL 与 ES 一致：通过 `TransactionSynchronization` 在事务 **afterCommit**
> 阶段写 ES，避免事务回滚导致 ES 脏数据；失败落 `es_retry_task` 重试表，
> 定时任务从 MySQL 反查最新数据补偿重放，超限标记 DEAD 待人工介入。
> 针对「用户搜字段中文名但字段值不含该词」的问题，设计 `__alias` 别名字段方案
> （字段中文名+同义词拼段参与检索），并用 `cross_fields` 替代 `best_fields`
> 解决多词跨字段 AND 匹配返回空的问题。提供全量同步接口与 14 条断言验收脚本，
> 全量同步一致性达 5/5，中文搜索命中率从 0 提升至 100%。
