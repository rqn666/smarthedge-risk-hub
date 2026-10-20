-- ============================================================
-- 知识库（RAG）表结构 · PostgreSQL + pgvector
--
-- 库：vec_db（容器 pg-vec，pgvector/pgvector:0.7.2-pg16，端口 5432）
-- 执行：docker exec -i pg-vec psql -U postgres -d vec_db < pgvector_knowledge.sql
--
-- 要点：
--  1. 维度固定 1024 —— 由 bge-m3 实测得出，换 embedding 模型必须改这里 + 重建向量
--  2. 向量列用 vector(1024)，索引用 HNSW + vector_cosine_ops（余弦距离）
--  3. kb_document 带状态机：PENDING -> PARSING -> INDEXED / FAILED，
--     上传是可能失败的（PDF 加密、无文字层），状态必须落库才能排查
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 文档元数据
CREATE TABLE IF NOT EXISTS kb_document (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(255)  NOT NULL,
    file_name    VARCHAR(512)  NOT NULL,
    -- 落地文件绝对路径：重建索引时不用让用户重传
    file_path    VARCHAR(1024),
    file_type    VARCHAR(32),
    file_size    BIGINT,
    chunk_count  INT           DEFAULT 0,
    status       VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    error_msg    VARCHAR(1024),
    uploader     VARCHAR(64),
    create_time  TIMESTAMP     DEFAULT now(),
    update_time  TIMESTAMP     DEFAULT now()
);

COMMENT ON TABLE  kb_document IS '知识库文档元数据';
COMMENT ON COLUMN kb_document.status IS 'PENDING/PARSING/INDEXED/FAILED';

-- 分块 + 向量
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1024),
    create_time TIMESTAMP    DEFAULT now()
);

COMMENT ON TABLE  kb_chunk IS '知识库分块与向量（bge-m3 1024 维）';

-- 向量索引：HNSW 近邻，余弦距离
CREATE INDEX IF NOT EXISTS idx_kb_chunk_embedding
    ON kb_chunk USING hnsw (embedding vector_cosine_ops);

-- 按文档删除/统计走这个
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc
    ON kb_chunk (doc_id);
