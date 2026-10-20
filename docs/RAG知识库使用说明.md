# RAG 知识库（私人知识库检索）· 使用与测试说明

> 状态：**已落地并端到端验证通过（15/15 断言全绿）**
> 本文所有命令都已实测可跑，可直接复制粘贴。

---

## 一、先说端口（避坑）

本项目 `application.yml` 里写的是 `server.port: 8081`，但**我验证时 8081 和 8082 都被你另一个带全局鉴权的应用占用了**
（那台机器任何路径都返回 401 `未提供登录令牌`，不是本项目），所以我把验证实例起在了 **18080**。

```
本说明中的地址一律用 http://127.0.0.1:18080
你自己的实例如果起在 8081，把端口换成 8081 即可，路径完全一样
```

---

## 二、启动前置（三项，缺一不可）

```bash
# 1. 向量库容器（必须在跑）
docker ps | grep pg-vec
# 没跑就起：docker start pg-vec

# 2. 建表（只需执行一次，脚本可重复执行）
docker exec -i pg-vec psql -U postgres -d vec_db < src/main/resources/sql/pgvector_knowledge.sql

# 3. 向量化模型（Ollama 必须有 bge-m3）
curl -s http://127.0.0.1:11434/api/tags | findstr bge-m3

# 4. 启动应用
mvn spring-boot:run
```

> 向量库连不上**不会阻塞应用启动**，只会打 WARN，知识库功能不可用、其他功能照常 —— 这是刻意设计的降级。

---

## 三、测试步骤（三步走）

### 第 1 步：确认知识库就绪

```bash
curl -s --noproxy '*' "http://127.0.0.1:18080/api/ai/kb/status"
```

期望看到：

```json
{
  "rag": {
    "available": true,
    "url": "jdbc:postgresql://127.0.0.1:5432/vec_db",
    "documents": 0, "chunks": 0,
    "dim": 1024, "embeddingModel": "bge-m3",
    "topK": 5, "minScore": 0.45
  }
}
```

`available: true` 且 `dim: 1024` 就对了。若为 `false`，看返回里的 `hint` 字段，它会告诉你缺什么。

### 第 2 步：上传 PDF 建索引

PDF 样本已生成好：`docs/kb/SmartHedge套期保值与风险管理制度手册.pdf`（5 页）
需要重新生成：`python tools/gen-kb-pdf.py`

```bash
curl -s --noproxy '*' -X POST "http://127.0.0.1:18080/api/ai/kb/upload" \
  -F "file=@D:/apesource/SmartHedge_Risk_Hub/docs/kb/SmartHedge套期保值与风险管理制度手册.pdf" \
  -F "title=SmartHedge套期保值与风险管理制度手册" \
  -F "uploader=admin"
```

期望返回：

```json
{"success":true,"data":{"docId":2,"status":"INDEXED","pages":5,"chunkCount":7,
 "tip":"已入库 7 个分块，可直接用 /api/ai/kb/search 检索验证"}}
```

> ⚠️ Windows 上用 curl 传文件，路径必须写成 `D:/...`，**不能写 `/d/...`**
> —— Windows 版 curl 不认 Git Bash 的路径格式，会直接读不到文件。

### 第 3 步：检索验证（不需要大模型，最快）

```bash
curl -s --noproxy '*' -G "http://127.0.0.1:18080/api/ai/kb/search" \
  --data-urlencode "query=单笔敞口超过多少需要报董事会审批"
```

这条能命中就说明向量检索通了。推荐测试问题（**答案只存在于 PDF，业务库里没有**，
所以命中就能证明走的是知识库而不是数据库）：

| 提问 | 期望在召回内容里看到 |
|---|---|
| 单笔敞口超过多少需要报董事会审批 | `5000 万元` |
| 内部保证金标准比交易所加收多少 | `加收 2 个百分点` |
| A级供应商的授信额度 | `3000 万元` |
| 浮动亏损多少要强制全部平仓 | `全部平仓` |
| 供应商年度复审时间要求 | `3 月 31 日` |
| 套保有效性达标线 | `80% 至 125%` |

### 第 4 步：端到端问答（大模型自动调知识库）

```bash
curl -s --noproxy '*' -G "http://127.0.0.1:18080/api/ai/ask" \
  --data-urlencode "message=按照公司制度，单笔套期保值敞口多大金额需要报董事会审批？"
```

**实测回答**（本地 qwen2.5:7b，`rounds=2`，耗时约 75s）：

> 根据《SmartHedge套期保值与风险管理制度手册》：
> 1. 单笔套期保值敞口金额超过 5000 万元（含）的套保申请，必须经公司董事会审议批准后方可执行。
> 2. 公司在交易所保证金标准基础上统一加收 2 个百分点作为内部风控缓冲。
>
> 参考：《SmartHedge套期保值与风险管理制度手册》

看两个点：`toolTrace` 里有没有 `searchKnowledgeBase`（模型自己决定去查知识库），
答案末尾有没有 `参考：《文档名》`（引用溯源）。

---

## 四、一键验收

```bash
cd /d/apesource/SmartHedge_Risk_Hub/SmartHedge_Risk_Hub
bash verify-rag.sh            # 默认 18080，含端到端
bash verify-rag.sh 18080 fast # 跳过端到端，只验环境与检索（快）
```

实测结果 **PASS=15 FAIL=0**：

```
A. 环境与数据     容器运行 / 表存在 / 扩展启用 / 连接可用 / 带向量分块 7 条
B. 检索命中       6 个问题全部命中且含证据关键词（73~108ms）
C. 端到端         自动调 searchKnowledgeBase ✓ / 事实正确 ✓ / 带引用溯源 ✓
```

---

## 五、接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/kb/upload` | 上传文档建索引（form-data：file / title / uploader） |
| GET | `/api/ai/kb/search` | 纯检索测试，`?query=xx&topK=5` |
| GET | `/api/ai/kb/status` | 向量库健康 + 文档/分块数 + 当前参数 |
| GET | `/api/ai/kb/documents` | 文档列表（含状态与失败原因） |
| GET | `/api/ai/kb/document` | 单文档详情，`?docId=1` |
| POST | `/api/ai/kb/reindex` | 重建索引（调了分块参数后用），`?docId=1` |
| DELETE | `/api/ai/kb/document` | 删除文档及其分块，`?docId=1` |
| GET | `/api/ai/kb/upload-dir` | 上传目录自检（存在/可写/文件数） |
| GET | `/api/ai/tools` | 查看已注册的 AI 工具（现在应是 4 个） |

---

## 六、可调参数（`application.yml` 的 `ai.rag`）

| 参数 | 当前值 | 调它的影响 |
|---|---|---|
| `enabled` | true | 关掉后检索直接返回空，对话照常（排障用） |
| `embedding-model` | bge-m3 | 换模型必须同步改 `embedding-dim` 和表里的 `vector(N)`，并重建向量 |
| `embedding-dim` | 1024 | 与 pgvector 表列维度必须一致，不一致会直接报错（已做校验） |
| `batch-size` | 8 | 单次向量化条数；调大更快但超时风险高 |
| `top-k` | 5 | 召回片段数；调大召回更全，但灌给大模型的噪声也更多 |
| `min-score` | 0.45 | **最需要按语料调的一个**。太高会「检索到却被过滤掉」，太低会灌无关内容 |
| `chunk-size` | 500 | 分块大小；调小提升精度、调大保留更多上下文 |
| `chunk-overlap` | 80 | 相邻块重叠，避免答案卡在切缝上 |
| `upload-dir` | 空 | 留空用工作目录下 `kb-uploads/` |

---

## 七、已知限制（如实说明，别在简历上吹过）

1. **Top-1 精度一般**：问题最相关的那一块不一定排第一。因为制度手册章节内容较长，
   一块里混合了多个主题。返回 5 块一起给模型，所以答案通常仍然正确 ——
   但如果你要更准，应该调小 `chunk-size`（比如 300）或加一层 rerank。
2. **语义检索不认「不存在」**：问「白糖套保额度」，因为制度手册讲的是通用套保原则，
   仍会召回 5 条（相似度 0.64）。模型会如实说「手册中没有明确指出」，不会编造 ——
   防幻觉是靠提示词约束 + 工具返回的 `未检索到` 提示，不是靠向量库。
3. **上传接口暂未接鉴权**：`/api/ai/kb/**` 目前谁都能访问。方案里的管理员鉴权（Shiro）属 M4 后续，
   **现在不要暴露到公网**。
4. **扫描件 PDF 不支持**：没有文字层的 PDF 会明确报错提示需要 OCR，不会静默建出空文档。
5. **单文件上限 100MB**：`spring.servlet.multipart.max-file-size` 已从默认 1MB 放开到 100MB。

---

## 八、踩过的坑（改代码时别再踩）

| 坑 | 症状 | 原因与解法 |
|---|---|---|
| 自建 DataSource Bean | MyBatis-Plus 数据源失效、应用起不来 | Spring Boot 的 `DataSourceAutoConfiguration` 带 `@ConditionalOnMissingBean`，多一个 DataSource Bean 就整体退让。**解法：连接池作为组件私有字段持有，不注册 Bean** |
| PDF 硬换行 | 检索召回半句话「加收 2」 | PDF 抽取按视觉行换行，句中被折断。`TextChunker.normalize` 已还原折行 |
| reportlab 中文 | 中文变黑块或报错 | 必须注册 CJK 字体，用内置 `STSong-Light`（`UnicodeCIDFont`），无需字体文件 |
| 不关上传流 | 日志刷 `UncheckedIOException: Cannot delete ...tmp` | Windows 会锁住未关闭的流。**必须 try-with-resources 关闭** |
| 默认 1MB 上限 | 上传报 `MaxUploadSizeExceededException` | Spring Boot 默认单文件 1MB，已在 yml 放开 |
| pgvector 写向量 | 插入报类型错误 | `?::vector` 显式转型，字面量形如 `[0.1,0.2,...]` |
