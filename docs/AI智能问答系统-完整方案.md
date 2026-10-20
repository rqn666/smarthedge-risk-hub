# SmartHedge_Risk_Hub · AI 智能问答系统完整方案（v2 · 已定稿选型）

> 目标：在现有套保/风控系统上，加一套 **Function Calling 智能问答 + 私人知识库（RAG）** 能力。
> 助手人设：**大宗商品智能问答助手**。管理员上传本地文件建库，用户提问时先检知识库再作答。
>
> v2 变更（果果拍板）：向量库 **pgvector**；LLM **deepseek-r1:7b**；鉴权 **Apache Shiro**；
> 并补入 **r1 不支持原生 tool calling 的实测结论与对策**。

---

## 1. 现状盘点（基于实际读码 + 实测）

| 项 | 现状 |
|---|---|
| 框架 | Spring Boot **2.7.15**，单模块 jar，`com.ape.smarthedge_risk_hub` |
| JDK | **1.8.0_251**（`C:\Program Files\Java\jdk1.8.0_251`，唯一 JDK） |
| 持久层 | MyBatis-Plus 3.5.1 + MySQL 8（`smarthedge_risk_hub`） |
| 已有业务 | 期货合约 / 期权合约 / 合约条款文本 / 现货产品 / 产地 / 品种 / 交易所 / 供应商 / 操作员 |
| 缺失 | 无鉴权、无缓存、无中间件、无 AI 依赖 |
| 环境 | Docker 29.5.2 在跑（nacos）；Ollama 本地有 `qwen2.5:7b`、`deepseek-r1:7b`、`bge-m3` |
| **本机 Maven** | `mvn`/`mvn.cmd` **命令行均报 `classworlds.Launcher` 找不到**，需用 `java -cp` 直调启动类（见 §10） |

可复用 Service（Tools 直接挂）：
`IFuturesContractService`、`IFuturesContractTextService`、`IOptionsContractService`、
`IOptionContractTextService`、`ISpotProductService`、`ISupplyService`、`ITradingVarietyService`、`IOriginDictService`

---

## 2. 硬约束与两条关键结论

**约束一：JDK 8 → Spring AI 官方不可用**（1.x 需 Boot 3.2+ / JDK 17）。
→ **自研 Function Calling 编排层**，协议对齐 OpenAI 兼容格式，换模型只改 yml。

**约束二（实测）：`deepseek-r1:7b` 不支持原生 Function Calling。**

本机实测（同一份带 `tools` 的请求打到 Ollama `/v1/chat/completions`）：

| 模型 | 传 `tools` 后返回 | 结论 |
|---|---|---|
| `deepseek-r1:7b` | `tool_calls: NONE`，直接编造回答（"建议上网查实时信息"） | **不支持原生 tools** |
| `qwen2.5:7b` | `tool_calls: [{name: queryFuturesContractSpec, args:{contractCode: RB2410}}]` | 支持原生 tools |

r1 的 ReAct 提示词模式补充实测（`/api/chat`，`format: json`）：
- 能输出结构化 JSON：`{"tool":"queryFuturesContractSpec","args":{"contractCode":"RB2410"}}` ✓
- 但会**顺手伪造 `"result":{"lastTradeDate":"2024-05-12"}`**（幻觉），必须靠工具回灌纠正
- `think:false` 未生效，仍输出思考；单轮耗时 **21.9s**（202 tokens）

**人设服从度实测**（同一份系统人设，问「写一首关于春天的诗」）：

| 模型 | 表现 |
|---|---|
| `deepseek-r1:7b` | **完全无视人设**，直接写 20 行中英混杂的诗，通篇无一句引导 |
| `qwen2.5:7b` | 开头声明「这与我作为大宗商品、套期保值与风险管理领域的助手有所偏离」，并把诗改成套保主题 |

**耗时实测**：同一组 5 条断言，r1 全程 63.5s，qwen2.5 全程 20.8s。

→ **结论：主用 qwen2.5:7b（native 模式），r1 作为可选（prompt 模式）。LLM 适配层做双模（`ai.llm.tool-mode`）**
- `prompt`（默认，给 r1）：工具清单注入系统提示词，模型输出严格 JSON，后端解析→执行→结果回灌，循环 ≤3 轮
- `native`（给 qwen2.5 / 云端）：走 OpenAI `tools` 协议
两种模式对上层 ChatOrchestrator 暴露同一接口，改 yml 一行即可切换。

---

## 3. 选型（已定稿）

| 能力 | 选择 | 说明 |
|---|---|---|
| LLM | **qwen2.5:7b**（Ollama） | `tool-mode: native`；备选 `deepseek-r1:7b`（`prompt` 模式）、云端 qwen-plus |
| Embedding | **bge-m3**（1024 维） | 已装；pgvector 列维度必须一致 `vector(1024)` |
| 向量库 | **pgvector**（Postgres 16） | `pgvector/pgvector:pg16` 镜像 |
| 关系库 | MySQL 8（业务/会话/轨迹） + PG（知识库/向量） | **双库，见 §7 多数据源设计** |
| 文档解析 | **PDFBox 3.0.3**（本地仓库已有）+ **POI 5.2.5**（需下载） | 覆盖 pdf/docx/xlsx/pptx/txt/md |
| 鉴权 | **Apache Shiro 1.13.0** | `shiro-spring-boot-web-starter`，管理员角色守卫 `/api/admin/**` |
| 流式 | SseEmitter | Boot 2.7 原生，Java 8 可用 |

---

## 4. 依赖清单与「要不要额外下载」

| 依赖 | 本机仓库 | 是否要下载 | 体积量级 |
|---|---|---|---|
| `org.apache.pdfbox:pdfbox:3.0.3` | **已有**（含 fontbox/pdfbox-io） | 否 | — |
| `org.apache.poi:poi-ooxml:5.2.5` | 无 | **是** | ~15MB（含 ooxml-schemas） |
| `org.apache.shiro:shiro-spring-boot-web-starter:1.13.0` | 无 | **是** | ~2MB |
| `org.postgresql:postgresql:42.7.x` | 需确认 | **是** | ~1MB |
| `spring-boot-starter-web / jackson` | 已有 | 否 | — |

**结论：文档解析不需要装任何独立软件，只是 Maven 拉 jar。** 已配置阿里云镜像
（`~/.m2/settings.xml` → `maven.aliyun.com`），POI + Shiro + PG 驱动合计约 20MB，几十秒完成。
M1 阶段暂不引入新依赖，先用 Boot 自带的 `RestTemplate` + Jackson 打通链路。

```xml
<!-- M4 解析 -->
<dependency><groupId>org.apache.pdfbox</groupId><artifactId>pdfbox</artifactId><version>3.0.3</version></dependency>
<dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.2.5</version></dependency>
<!-- 鉴权 -->
<dependency><groupId>org.apache.shiro</groupId><artifactId>shiro-spring-boot-web-starter</artifactId><version>1.13.0</version></dependency>
<!-- 向量库 -->
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.4</version></dependency>
```

**pgvector 启动**
```bash
docker run -d --name pgvector -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=smarthedge_kb ^
  -p 5432:5432 -v pgvector_data:/var/lib/postgresql/data pgvector/pgvector:pg16
```

---

## 5. 整体架构

```
接入层   AiChatController(/api/ai/chat, SSE)  ·  AdminKbController(/api/admin/kb/*)
                        ↓
编排层   ChatOrchestrator：系统提示词 + 工具循环(≤3轮) + 溯源拼装
                        ↓
能力层   ToolRegistry(9 个业务 Tool)   ·   RAG 流水线(解析→分块→向量化)
                        ↓
依赖     deepseek-r1:7b   bge-m3   pgvector(PG16)   MySQL8
                     ↕ Shiro 守卫 /api/admin/**
```

目录（新增 `ai` 包，不动现有代码）：
```
com.ape.smarthedge_risk_hub.ai
├── config     AiProperties · PgVectorConfig(独立 JdbcTemplate)
├── client     LlmClient(native/prompt 双模) · EmbeddingClient(bge-m3)
├── chat       ChatOrchestrator · SystemPromptLoader · ChatSessionService
├── tool       @Tool · @ToolParam · ToolDefinition · ToolRegistry · ToolExecutor
├── tool.impl  ContractTools · SpotTools · SupplierTools · CalcTools · KnowledgeTools
├── rag        DocumentParser(PDFBox/POI) · TextSplitter · VectorStoreService · KnowledgeBaseService
├── shiro      AdminRealm · ShiroConfig
└── controller AiChatController · AdminKbController
resources/prompt/system-prompt.txt
```

---

## 6. LLM 适配层（双模核心）

**prompt 模式（r1）流程**
1. 系统提示词尾部注入「可用工具清单 + 输出格式约定 + 1 个 few-shot 示例」；
2. 要求模型需要数据时**只**输出：
   `{"tool":"queryFuturesContractSpec","args":{"contractCode":"RB2410"}}`
   不需要工具时输出：`{"tool":"none","answer":"..."}`；
3. 后端从返回文本中剥离 `<think>…</think>` 与 ```json 围栏，取第一个合法 JSON；
4. **禁止模型自带 `result` 字段**（实测它爱伪造），取到就丢弃并记 warn 日志；
5. 执行工具 → 结果以 `OBSERVATION:` 追加回 messages → 再请求，循环 ≤3 轮；
6. 解析连续失败 2 次 → 直接把模型文本当最终答案返回，不报错。

**native 模式（qwen2.5 / 云端）**
走标准 `POST /v1/chat/completions` + `tools` + `tool_choice:auto`，解析 `tool_calls`，
结果以 `role=tool` 回灌，同一套循环逻辑。

---

## 7. pgvector 与双库设计

**关键决策：知识库整体放 PG，业务/会话留 MySQL，PG 用独立 `JdbcTemplate`，不接入 MyBatis-Plus。**
理由：避免给现有项目引入多数据源 + `@DS` 切换的复杂度，MyBatis-Plus 只管 MySQL 不动。

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_document (
  id BIGSERIAL PRIMARY KEY, doc_name VARCHAR(255) NOT NULL, file_type VARCHAR(16),
  file_size BIGINT, category VARCHAR(64), status VARCHAR(16),
  chunk_count INT DEFAULT 0, error_msg VARCHAR(512),
  uploader VARCHAR(64), create_time TIMESTAMP, update_time TIMESTAMP);

CREATE TABLE kb_chunk (
  id BIGSERIAL PRIMARY KEY, doc_id BIGINT, seq INT, page_no INT,
  content TEXT NOT NULL, token_count INT, category VARCHAR(64),
  enabled BOOLEAN DEFAULT TRUE, embedding VECTOR(1024), create_time TIMESTAMP);

CREATE INDEX idx_kb_chunk_embedding ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_kb_chunk_doc ON kb_chunk(doc_id);
```

检索（余弦距离 `<=>`，分数 = 1 - distance）：
```sql
SELECT id, doc_id, content, 1 - (embedding <=> ?::vector) AS score
FROM kb_chunk WHERE enabled = TRUE
ORDER BY embedding <=> ?::vector LIMIT 5;
```

MySQL 侧（沿用现有库）：`ai_chat_session`、`ai_chat_message`、`ai_tool_trace`。

---

## 8. Shiro 管理员鉴权

```java
@Bean ShiroFilterFactoryBean shiroFilter(DefaultWebSecurityManager sm) {
    Map<String,String> chain = new LinkedHashMap<>();
    chain.put("/api/admin/login", "anon");
    chain.put("/api/admin/**", "roles[admin]");
    chain.put("/api/ai/**", "anon");
    ...
}
```
- `AdminRealm`：查 `operator` 表（现有 `Operator` 实体 + `OperatorMapper`），校验账号密码，
  授权 `admin` 角色；密码存明文现状下先做等值校验，后续加盐哈希。
- 登录：`POST /api/admin/login` → `Subject.login(UsernamePasswordToken)` → 返回 `sessionId`，
  前端后续请求带 `Authorization: Bearer <sessionId>`（自定义 `AdminSessionFilter` 解析 header，
  前后端分离不用 cookie）。
- 上传接口走 `/api/admin/kb/**`，天然被 `roles[admin]` 保护。

**三个已知坑（写代码时逐条验证，对应 §12 断言）**
1. Boot 2.7 + Shiro starter 会把 `shiroFilter` 注册进 Boot 的 filter chain，
   可能与 CORS/其他 filter 顺序冲突 → 需实测 OPTIONS 预检不被拦截。
2. filter chain 的路径写法（带不带 `/`、`/**` 粒度）版本敏感，以断言实测为准。
3. Shiro 默认 session 依赖 cookie，前后端分离必须自定义 header token filter，否则登录态丢失。

---

## 9. Tools 清单（挂现有 Service）

| # | name | 说明 |
|---|---|---|
| 1 | queryFuturesContract | 期货合约列表/详情 |
| 2 | queryFuturesContractSpec | 期货条款：交易单位、涨跌停、最低保证金、最后交易日规则 |
| 3 | queryOptionsContract | 期权合约列表/详情 |
| 4 | queryOptionsContractSpec | 期权条款：行权价规则、行权方式、到期日 |
| 5 | querySpotProduct | 现货产品 + 产地/规格/单位 |
| 6 | querySupplier | 供应商评分、状态、供货类别 |
| 7 | queryTradingVariety | 品种与交易所字典 |
| 8 | calcHedgeMargin | 保证金占用 / 套保手数 / 盈亏（禁止模型口算） |
| 9 | searchKnowledgeBase | 知识库语义检索，可指定 category |

---

## 10. 系统提示词（`resources/prompt/system-prompt.txt`）

```
# 角色
你是「SmartHedge 大宗商品智能问答助手」，服务于套期保值与风险管理业务，
领域覆盖：期货/期权合约与条款、现货产品与产地、供应商与授信、套保测算、风控制度。

# 工具调用规则（重要）
需要业务数据或文档依据时，必须只输出一行 JSON，不要输出其他文字：
{"tool":"工具名","args":{"参数名":"值"}}
不需要调用工具时输出：{"tool":"none","answer":"你的回答"}
严禁自己编造 args 之外的 result / 数据字段，工具结果会由系统回灌给你。

# 工作规则
1. 涉及合约条款、制度流程、历史文档的问题，先 searchKnowledgeBase 检索知识库，
   查到依据再回答，末尾用「参考：《文档名》」标注来源。
2. 涉及实时业务数据（合约、现货、供应商、品种）调用对应查询工具，
   禁止凭记忆编造合约代码、保证金比例、价格、评分。
3. 工具返回为空可换参数重试一次；仍无结果就说「当前知识库/业务库中未检索到相关依据」。
4. 计算类问题（保证金、套保手数、盈亏）必须调 calcHedgeMargin，保留 2 位小数并说明公式。
5. 最多调用 3 轮工具，之后必须给出结论。

# 回答规范
- 中文，结论先行 → 关键数据 → 参考来源。
- 术语准确：基差、套保比例、保证金、行权价，不混用口语。
- 涉及风险敞口与套保决策时附风险提示，并声明「不构成投资建议」。
- 与大宗商品无关的问题礼貌拒答并引导回主题。

# 语气
专业、克制、有依据。不知道就说不知道。
```

---

## 11. 接口设计

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/ai/chat` | 非流式问答（答案 + 来源 + 轨迹） | 匿名 |
| GET | `/api/ai/chat/stream` | SSE 流式 | 匿名 |
| GET | `/api/ai/session/*` | 会话列表/历史/删除 | 匿名 |
| POST | `/api/admin/login` | 管理员登录（Shiro） | anon |
| POST | `/api/admin/kb/upload` | 上传文件建库 | roles[admin] |
| GET | `/api/admin/kb/list` | 文档列表与状态 | roles[admin] |
| GET | `/api/admin/kb/{id}/status` | 解析进度 | roles[admin] |
| DELETE | `/api/admin/kb/{id}` | 删文档（同步删向量） | roles[admin] |
| POST | `/api/admin/kb/{id}/reindex` | 重建索引 | roles[admin] |

---

## 12. 里程碑

| 阶段 | 内容 | 完成标志 |
|---|---|---|
| M1 | AiProperties + LlmClient(双模) + 系统提示词 + 单轮对话 | **✅ 已完成**：5 条断言全绿（`mvn test -Dtest=AiLlmSmokeTest`） |
| M2 | @Tool / ToolRegistry / 工具循环（prompt 模式） | **✅ 已完成**：3 个业务工具 + `BeanPostProcessor` 扫描 + 3 轮上限 + 工具轨迹；native 模式实测跑通 |
| M3 | 9 个业务 Tools 接入现有 Service | ⏳ 部分完成：已做 3 个（期货条款 / 期货合约 / 现货），尚缺 6 个 |
| M4 | Shiro 鉴权 + 文档上传/解析/分块/向量化/入 pgvector | ✅ **功能已完成**：上传→PDFBox 解析→分块→bge-m3 向量化→pgvector 入库，状态机 PENDING/PARSING/INDEXED/FAILED；**Shiro 鉴权未做**（上传接口当前无鉴权，勿暴露公网） |
| M5 | RAG 双路召回 + 引用溯源 + SSE 流式 + 会话持久化 | ⏳ 部分完成：向量召回 + `searchKnowledgeBase` 工具接入 + 引用溯源已跑通（实测答案带「参考：《文档名》」）；**双路召回 / SSE 流式 / 会话持久化未做** |
| M6 | 超时重试降级、审计日志、上传安全、断言测试 | ⏳ 部分完成：向量库挂掉降级、检索失败降级、分块器单元测试 6/6、`verify-rag.sh` 15/15；**审计日志 / 上传内容安全扫描未做** |

### M4/M5 落地产物（2026-09-23）

新增 7 个 Java 类 + 1 个 DDL + 1 个验收脚本 + 1 个 PDF 生成脚本：

```
ai/rag/EmbeddingClient.java      Ollama bge-m3 批量向量化（/api/embed），维度校验
ai/rag/DocumentParser.java       PDFBox 解析 pdf/txt/md；扫描件与加密PDF明确报错；GBK 自动兜底
ai/rag/TextChunker.java          中文分块，边界优先（段落>句子>硬切），PDF 折行还原，绝不空转
ai/rag/PgVectorStore.java        pgvector 读写；连接池私有持有（不注册 Bean）；单事务批量写
ai/rag/KnowledgeService.java     编排 + 状态机；路径穿越防护；流必须关闭
ai/tool/impl/KnowledgeTools.java @Tool searchKnowledgeBase，接入 Function Calling
ai/controller/KnowledgeController.java  8 个管理/测试接口
resources/sql/pgvector_knowledge.sql     kb_document + kb_chunk + HNSW 索引
verify-rag.sh                    15 条断言
tools/gen-kb-pdf.py              生成中文 PDF 知识库样本（reportlab + STSong-Light）
```

验收：`bash verify-rag.sh 18080` → **PASS=15 FAIL=0**。

---

## 13. 风险与对策

| 风险 | 对策 |
|---|---|
| r1 不支持原生 tools（实测） | prompt 模式 ReAct + 双模可切换 |
| r1 伪造工具结果（实测） | 丢弃 `result` 字段 + 提示词明令禁止 + warn 日志 |
| r1 `think:false` 无效、单轮 ~22s | 限制 3 轮、SSE 流式展示思考过程、必要时换 qwen2.5 |
| 中文分块割裂语义 | 按标点切句 + 500 token 合并 + 80 token 重叠 |
| 扫描版 PDF 无文字层 | 解析为空标记 `FAILED: 疑似扫描件`，本期不做 OCR |
| pgvector 不可用 | 降级 MySQL `LIKE` 关键词检索 |
| Shiro 与 Boot filter 顺序/CORS 冲突 | §8 三个坑逐条实测断言 |
| 上传安全 | 类型白名单 + ≤20MB + 文件名随机化 + 防路径穿越 |

---

## 14. 验收断言（可复跑）

1. **人设**：问「写一首诗」→ 拒答并引导回大宗商品主题。
2. **工具触发**：问「RB2410 最后交易日」→ `ai_tool_trace` 出现 `queryFuturesContractSpec`。
3. **查库准确**：问「螺纹钢有哪些合约」→ 条数与 `futures_contract` 按品种计数一致。
4. **入库**：上传《套保管理制度》→ `kb_document.status=FINISHED`、`chunk_count>0`、
   `kb_chunk` 中 embedding 非空的条数一致。
5. **召回**：问「套保方案审批流程」→ 命中该 PDF 的 chunk，`score ≥ 0.6`，答案含「参考：《套保管理制度》」。
6. **溯源一致**：答案引用的 chunk_id 必须能在 `kb_chunk` 查到原文。
7. **降级**：停掉 pgvector 容器 → 接口仍 200，标注「知识库暂不可用」。
8. **鉴权**：不带 token 调 `/api/admin/kb/upload` → 被 Shiro 拦下（401/302 重定向登录）。
9. **循环上限**：构造必然失败的参数 → ≤3 轮终止并返回兜底话术。
10. **幻觉防护**：r1 返回含 `result` 字段时 → 系统丢弃该字段并记 warn。

---

## 15. 本机环境备忘

Maven 命令行在本机不可用（`mvn` / `mvn.cmd` 均报 `classworlds.Launcher` 找不到），
改用直调启动类：
```bash
java -classpath "D:\develop\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=D:\develop\apache-maven-3.9.11\bin\m2.conf" \
  "-Dmaven.home=D:\develop\apache-maven-3.9.11" \
  "-Dmaven.multiModuleProjectDirectory=D:\apesource\SmartHedge_Risk_Hub\SmartHedge_Risk_Hub" \
  org.codehaus.plexus.classworlds.launcher.Launcher <goals>
```
