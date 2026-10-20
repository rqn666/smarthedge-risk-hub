package com.ape.smarthedge_risk_hub.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 能力配置，对应 application.yml 的 ai.* 段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Llm llm = new Llm();
    private Rag rag = new Rag();

    @Data
    public static class Llm {
        /** OpenAI 兼容地址；Ollama 为 http://localhost:11434 */
        private String baseUrl = "http://localhost:11434";
        private String model = "deepseek-r1:7b";
        /**
         * prompt: 工具清单写进提示词，模型吐 JSON，后端解析（deepseek-r1 走这个）
         * native: 走 OpenAI tools 协议（qwen2.5 / 云端模型走这个）
         */
        private String toolMode = "prompt";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 180;
        private int maxToolRounds = 3;
        /**
         * 流式接口（/api/ai/chat/stream）的工具轮次，默认 <b>1</b> 轮。
         *
         * <p>流式必须把「工具阶段」和「生成阶段」拆开：工具阶段只能非流式，
         * 因为要先拿到完整的 tool_calls 才能执行。所以每多判断一轮，
         * 就多一次几十秒的阻塞推理（本地 7B 约 30~60s），用户会觉得"卡住了"。
         * 流式场景优先保证尽快出字；确实需要多轮工具的问题请走非流式 /api/ai/ask。
         */
        private int streamToolRounds = 1;
        private double temperature = 0.3;
    }

    @Data
    public static class Rag {
        /** RAG 总开关：false 时知识库检索直接返回空，对话照常（不阻塞主流程） */
        private boolean enabled = true;
        /** 向量化模型；维度必须与 pgvector 表的 vector(N) 一致 */
        private String embeddingModel = "bge-m3";
        /** bge-m3 实测 1024 维；换模型必须同步改 DDL 里的 vector(N) 并重建向量 */
        private int embeddingDim = 1024;
        /** 单次批量向量化条数；越大越快但超时风险高 */
        private int batchSize = 8;
        private int topK = 5;
        /**
         * 相似度下限（余弦，1 - 距离）。
         * bge-m3 中文语义命中通常在 0.5~0.75，无关内容在 0.3 以下；
         * 阈值太高会「检索到却过滤掉」，太低会把无关片段灌给大模型造成幻觉。
         */
        private double minScore = 0.45;
        private int chunkSize = 500;
        private int chunkOverlap = 80;
        /** 上传文件存放目录；留空则用 java.io.tmpdir/kb-uploads */
        private String uploadDir = "";
        /** 向量库（pgvector）连接，独立于业务 MySQL */
        private Pg pg = new Pg();

        @Data
        public static class Pg {
            private String url = "jdbc:postgresql://127.0.0.1:5432/vec_db";
            private String username = "postgres";
            /** pgvector 密码，默认空；真实值通过环境变量 PG_PASSWORD 注入 */
            private String password = "";
            /** 连接池大小；知识库是低频写、中频读，不需要大池 */
            private int maxPoolSize = 5;
            private int connectTimeoutSeconds = 5;
        }
    }

    /** 保留给后续知识库分类过滤使用 */
    private List<String> categories = new ArrayList<>();
}
