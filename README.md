# SmartHedge Risk Hub · 大宗商品套期保值风控平台

面向大宗商品**套期保值（Hedging）业务**的后台经营管理系统：覆盖期货/期权合约档案与条款、现货商品、供应商等基础数据管理，内置 Elasticsearch 全文检索、注解式操作日志，以及 AI 制度知识库问答（RAG）与 AI 合约文本解析入库两项智能能力。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 基础框架 | Spring Boot 2.7.15 / Spring MVC / JDK 8 |
| 持久层 | MyBatis-Plus 3.5.1 + MySQL 8 |
| 全文检索 | Elasticsearch 7.16.2 + IK 中文分词 |
| 向量库 | PostgreSQL + pgvector（HNSW 余弦索引，1024 维） |
| AI | Ollama 本地大模型（qwen2.5:7b 对话 / bge-m3 向量化） |
| 其他 | Spring AOP + @Async、Apache PDFBox 2.0.30 |

## 功能模块

### 1. 基础数据管理
- 期货/期权合约档案与合约条款（品种、交易所、交易单位、保证金比例、涨跌停板、最后交易日等字段）
- 现货商品、供应商与供应分类、交易品种与交易所字典、操作员管理
- 单表 CRUD 基于 MyBatis-Plus BaseMapper + Lambda 条件构造器；多表关联与模糊查询采用注解式 SQL，配合 MP 分页插件自动分页

### 2. Elasticsearch 全文检索
- MySQL 事务提交后双写 ES，保证两边数据一致；写入失败落重试表，由定时任务按可配次数/间隔补偿
- ES 不可用时搜索接口自动降级（返回 503），业务写入零影响；`es.enabled=false` 可一键回退纯数据库模式
- 解决 MySQL 模糊查询全表扫描问题，检索耗时降至 2~28ms

### 3. 注解式操作日志
- 自定义 `@OperLog` 注解 + Spring AOP 环绕通知，业务方法只需声明模块与动作，由切面统一采集方法名、请求参数、客户端 IP、耗时与执行结果
- 日志经 `@Async` 独立线程池异步落库，与主业务解耦；入库失败仅打印告警，不影响接口正常响应

### 4. AI 制度知识库问答（RAG）
- 上传 PDF（PDFBox 解析）→ 按 500 字切分、相邻分块保留 80 字重叠 → bge-m3 向量化 → 存入 PostgreSQL + pgvector
- 自然语言提问：语义检索召回 Top-5（余弦相似度阈值 0.45）→ 连同问题交给本地 Ollama 生成答案 → 答案标注来源文档与分块序号，支持 SSE 流式逐字返回
- 约束模型只依据召回片段作答，降低幻觉

### 5. AI 合约文本解析入库（Function Calling）
- 合约原文发给本地大模型，由 Function Calling 驱动模型自动抽取结构化字段并调用入库工具
- 「预览 + 确认」两步落库：先回显解析结果并做品种、交易所字典校验，再凭服务端下发的一次性令牌确认写入（令牌伪造或重复使用一律拒绝）
- 校验不通过的字段返回错误提示，交人工复核，保证入库数据准确可追溯

## 快速开始

### 环境要求

- JDK 8+、Maven 3.6+
- MySQL 8（库名 `smarthedge_risk_hub`）
- Elasticsearch 7.16.2（需安装 IK 分词插件，客户端版本已在 pom 中锁死与服务端一致）
- PostgreSQL + pgvector 扩展（库名 `vec_db`，可用 Docker 镜像 `pgvector/pgvector:0.7.2-pg16`）
- Ollama 本地服务，拉取模型：`ollama pull qwen2.5:7b bge-m3`

### 启动步骤

1. 初始化 MySQL 重试表：执行 `SmartHedge_Risk_Hub/src/main/resources/sql/es_retry_task.sql`
2. 初始化向量库：`docker exec -i <pg容器名> psql -U postgres -d vec_db < SmartHedge_Risk_Hub/src/main/resources/sql/pgvector_knowledge.sql`
3. 数据库账号密码通过环境变量注入（配置文件中已脱敏为 `${环境变量:默认值}` 占位符，仓库不落明文）：
   - IDEA：Run Configuration → Environment variables 添加 `MYSQL_PASSWORD`、`PG_PASSWORD`
   - 命令行：`MYSQL_PASSWORD=xxx PG_PASSWORD=xxx mvn spring-boot:run`
4. 启动应用：

   ```bash
   cd SmartHedge_Risk_Hub
   mvn spring-boot:run
   ```

5. 访问 <http://localhost:8082>（端口以 application.yml 中 `server.port` 为准）

## 目录结构

```
SmartHedge_Risk_Hub/            # 仓库根目录
├── SmartHedge_Risk_Hub/        # Spring Boot 主工程
│   ├── src/main/java/com/ape/smarthedge_risk_hub/
│   │   ├── ai/                 # AI 问答、RAG 知识库、合约解析、Function Calling
│   │   ├── controller/         # 合约/现货/供应商等业务接口
│   │   ├── es/                 # ES 双写、检索接口、补偿重试
│   │   ├── aspect/             # @OperLog 操作日志切面
│   │   ├── service/ dao/ pojo/ common/ util/ configure/
│   │   └── SmartHedgeRiskHubApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml     # 全部配置（数据源 / ES / AI / RAG）
│   │   ├── sql/                # es_retry_task.sql、pgvector_knowledge.sql
│   │   └── prompt/             # 系统提示词
│   └── verify-*.sh             # 可复跑验收脚本（见下表）
├── kb-corpus/                  # 15 份套保制度语料（PDF，约 4.9 万字）
├── docs/                       # 方案文档与讲解材料
└── tools/                      # 语料生成、接口验证 Python 工具
```

## 验收脚本（可复跑）

每个功能都配有带断言的验收脚本，应用启动后直接运行：

| 脚本 | 验证内容 |
| --- | --- |
| `verify-es.sh` | ES 双写一致性、检索耗时、ES 不可用时的降级行为 |
| `verify-operlog.sh` | 正常/异常请求下操作日志落库完整性、异常不被吞 |
| `verify-rag.sh` | pgvector 环境、检索命中（含证据关键词）、端到端问答带引用溯源 |
| `verify-kb-corpus.sh` | 语料 PDF 解析切分入库、向量维度、关键词检索 |
| `verify-ai2.sh` | AI SSE 流式输出 + 合约文本解析入库（`fast` 参数可跳过慢的大模型调用） |
| `verify-fc-query.sh` | Function Calling 条件查询端到端 |

用法示例：`bash verify-es.sh 8082`（参数为应用端口）；涉及 MySQL 查询的脚本需带密码环境变量：`MYSQL_PASSWORD=xxx bash verify-es.sh 8082`。

## 配置说明

关键配置均在 `application.yml`：

| 配置项 | 说明 |
| --- | --- |
| `spring.datasource` | MySQL 业务库 |
| `es.*` | ES 集群地址、总开关、双写失败补偿（间隔/次数） |
| `ai.llm.*` | Ollama 地址、模型（qwen2.5:7b）、工具调用模式（native/prompt）、流式工具轮次 |
| `ai.rag.*` | RAG 总开关、向量模型（bge-m3）、维度、Top-K、相似度阈值、分块大小/重叠 |
| `ai.rag.pg.*` | pgvector 向量库连接 |

> AI 问答默认走 Ollama 本地模型，无需任何外部 API Key；`ai.rag.enabled=false` 可单独关闭知识库检索，对话功能不受影响。

## 说明

- 知识库语料（15 份套保制度文档）位于 `kb-corpus/`，由 `tools/gen-kb-docs.py` 生成、`tools/gen-kb-pdf.py` 转为 PDF
- 仓库 `docs/` 目录收录了各功能的落地文档与逐行讲解
