package com.ape.smarthedge_risk_hub.ai.rag;

import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector 向量存储（知识库）。
 *
 * ⚠️ 关键设计：**故意不注册 DataSource / JdbcTemplate 的 Spring Bean**。
 * 因为 Spring Boot 的 DataSourceAutoConfiguration 带 @ConditionalOnMissingBean(DataSource.class)，
 * 一旦容器里多出一个 DataSource Bean，它就会整体退让 —— 直接后果是业务库（MySQL / MyBatis-Plus）
 * 的数据源失效，整个项目起不来。所以这里把连接池作为**组件私有字段**持有，对外只暴露方法。
 *
 * 第二个设计：向量库连不上**不阻塞应用启动**。知识库是增强能力，不是主流程依赖，
 * 连不上就打 WARN，检索时返回空，业务照常跑。
 */
@Component
public class PgVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final AiProperties props;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private volatile boolean available = false;

    @Autowired
    public PgVectorStore(AiProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        AiProperties.Rag.Pg pg = props.getRag().getPg();
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(pg.getUrl());
            cfg.setUsername(pg.getUsername());
            cfg.setPassword(pg.getPassword());
            cfg.setDriverClassName("org.postgresql.Driver");
            cfg.setMaximumPoolSize(Math.max(1, pg.getMaxPoolSize()));
            // Hikari 要求 connectionTimeout >= 250ms
            cfg.setConnectionTimeout(Math.max(250, pg.getConnectTimeoutSeconds() * 1000));
            cfg.setPoolName("pgvector-pool");
            // 元数据接口不需要长事务
            cfg.setAutoCommit(true);

            this.dataSource = new HikariDataSource(cfg);
            this.jdbc = new JdbcTemplate(this.dataSource);

            // 真实探一次 + 校验表在不在，避免「连上了但没建表」到检索时才炸
            Integer tableCount = jdbc.queryForObject(
                    "select count(*) from information_schema.tables "
                            + "where table_schema='public' and table_name in ('kb_document','kb_chunk')",
                    Integer.class);
            if (tableCount == null || tableCount < 2) {
                log.error("[rag] vector 库已连上，但知识库表不存在。请先执行：\n"
                        + "      docker exec -i pg-vec psql -U {} -d vec_db < src/main/resources/sql/pgvector_knowledge.sql",
                        pg.getUsername());
                this.available = false;
                return;
            }
            this.available = true;
            log.info("[rag] pgvector 就绪: {} | 文档 {} 篇 / 分块 {} 条",
                    pg.getUrl(), countDocuments(), countChunks());
        } catch (Exception e) {
            this.available = false;
            log.warn("[rag] pgvector 初始化失败，知识库功能不可用（不影响其他功能）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception ignore) {
                // 关闭失败无需处理
            }
        }
    }

    public boolean isAvailable() {
        return available && jdbc != null && dataSource != null;
    }

    /** 向量库自身健康 + 数据量，供 /api/ai/kb/status 用 */
    public Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("available", available);
        map.put("url", props.getRag().getPg().getUrl());
        if (!isAvailable()) {
            map.put("hint", "向量库不可用：确认容器 pg-vec 在跑、库 vec_db 存在、且已执行 pgvector_knowledge.sql");
            return map;
        }
        map.put("documents", countDocuments());
        map.put("chunks", countChunks());
        map.put("dim", props.getRag().getEmbeddingDim());
        map.put("embeddingModel", props.getRag().getEmbeddingModel());
        map.put("topK", props.getRag().getTopK());
        map.put("minScore", props.getRag().getMinScore());
        return map;
    }

    // ==================== 写 ====================

    /** 建文档记录，返回自增主键。初始状态 PENDING */
    public long createDocument(String title, String fileName, String filePath, String fileType,
                               Long fileSize, String uploader) {
        requireAvailable();
        Long id = jdbc.queryForObject(
                "insert into kb_document(title, file_name, file_path, file_type, file_size, status, "
                        + "uploader, create_time, update_time) "
                        + "values (?,?,?,?,?, 'PENDING', ?, now(), now()) returning id",
                Long.class, title, fileName, filePath, fileType, fileSize, uploader);
        return id == null ? 0L : id;
    }

    public void updateStatus(long docId, String status, Integer chunkCount, String errorMsg) {
        requireAvailable();
        String sql = "update kb_document set status=?, error_msg=?, update_time=now()"
                + (chunkCount == null ? "" : ", chunk_count=?")
                + " where id=?";
        if (chunkCount == null) {
            jdbc.update(sql, status, errorMsg, docId);
        } else {
            jdbc.update(sql, status, errorMsg, chunkCount, docId);
        }
    }

    /**
     * 批量写入分块与向量。**单事务**：要么全进，要么全不进，
     * 避免出现「文档一半分块可检索」这种半成品状态。
     */
    public void insertChunks(long docId, List<String> contents, List<float[]> vectors) {
        requireAvailable();
        if (contents == null || contents.isEmpty()) {
            return;
        }
        if (contents.size() != vectors.size()) {
            throw new IllegalArgumentException("分块数与向量数不一致："
                    + contents.size() + " vs " + vectors.size());
        }

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            ps = conn.prepareStatement(
                    "insert into kb_chunk(doc_id, chunk_index, content, embedding, create_time) "
                            + "values (?,?,?,?::vector, now())");
            for (int i = 0; i < contents.size(); i++) {
                ps.setLong(1, docId);
                ps.setInt(2, i);
                ps.setString(3, contents.get(i));
                ps.setString(4, toVectorLiteral(vectors.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception ignore) {
                    // 回滚失败也不掩盖原始异常
                }
            }
            throw new IllegalStateException("写入分块失败：" + e.getMessage(), e);
        } finally {
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    /** 删文档（分块由外键 ON DELETE CASCADE 自动清理） */
    public boolean deleteDocument(long docId) {
        requireAvailable();
        return jdbc.update("delete from kb_document where id=?", docId) > 0;
    }

    /** 清掉某文档的分块但保留文档记录（重建用） */
    public int deleteChunks(long docId) {
        requireAvailable();
        return jdbc.update("delete from kb_chunk where doc_id=?", docId);
    }

    // ==================== 读 ====================

    /**
     * 向量相似度检索。
     * 用 pgvector 的余弦距离算子 {@code <=>}，相似度 = 1 - 距离。
     * 阈值过滤放在 SQL 里（LIMIT 之前），避免把低于阈值的片段捞回来再丢掉。
     */
    public List<SearchHit> search(float[] queryVector, int topK, double minScore) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        if (queryVector == null || queryVector.length == 0) {
            return Collections.emptyList();
        }
        int k = topK <= 0 ? props.getRag().getTopK() : topK;
        String literal = toVectorLiteral(queryVector);

        String sql = "select c.id, c.doc_id, c.chunk_index, c.content, "
                + "       d.title, d.file_name, "
                + "       1 - (c.embedding <=> ?::vector) as score "
                + "from kb_chunk c join kb_document d on d.id = c.doc_id "
                + "where c.embedding is not null and d.status = 'INDEXED' "
                + "  and 1 - (c.embedding <=> ?::vector) >= ? "
                + "order by c.embedding <=> ?::vector "
                + "limit ?";

        List<SearchHit> hits = new ArrayList<SearchHit>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, literal);
            ps.setString(2, literal);
            ps.setDouble(3, minScore);
            ps.setString(4, literal);
            ps.setInt(5, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchHit hit = new SearchHit();
                    hit.chunkId = rs.getLong("id");
                    hit.docId = rs.getLong("doc_id");
                    hit.chunkIndex = rs.getInt("chunk_index");
                    hit.content = rs.getString("content");
                    hit.title = rs.getString("title");
                    hit.fileName = rs.getString("file_name");
                    hit.score = rs.getDouble("score");
                    hits.add(hit);
                }
            }
        } catch (Exception e) {
            log.warn("[rag] 向量检索失败，降级返回空: {}", e.getMessage());
            return Collections.emptyList();
        }
        return hits;
    }

    public List<Map<String, Object>> listDocuments() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        return jdbc.query(
                "select id, title, file_name, file_type, file_size, chunk_count, status, "
                        + "error_msg, uploader, create_time, update_time "
                        + "from kb_document order by id desc",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("fileType", rs.getString("file_type"));
                    m.put("fileSize", rs.getObject("file_size"));
                    m.put("chunkCount", rs.getInt("chunk_count"));
                    m.put("status", rs.getString("status"));
                    m.put("errorMsg", rs.getString("error_msg"));
                    m.put("uploader", rs.getString("uploader"));
                    Timestamp ct = rs.getTimestamp("create_time");
                    m.put("createTime", ct == null ? null : ct.toLocalDateTime().toString());
                    return m;
                });
    }

    public Map<String, Object> getDocument(long docId) {
        if (!isAvailable()) {
            return null;
        }
        List<Map<String, Object>> list = jdbc.query(
                "select id, title, file_name, file_path, file_type, chunk_count, status, error_msg "
                        + "from kb_document where id=?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("filePath", rs.getString("file_path"));
                    m.put("fileType", rs.getString("file_type"));
                    m.put("chunkCount", rs.getInt("chunk_count"));
                    m.put("status", rs.getString("status"));
                    m.put("errorMsg", rs.getString("error_msg"));
                    return m;
                }, docId);
        return list.isEmpty() ? null : list.get(0);
    }

    public int countDocuments() {
        return countInternal("select count(*) from kb_document where status='INDEXED'");
    }

    public int countChunks() {
        return countInternal("select count(*) from kb_chunk");
    }

    private int countInternal(String sql) {
        try {
            Integer c = jdbc.queryForObject(sql, Integer.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * float[] -> pgvector 字面量 "[0.1,0.2,...]"。
     * 用 StringBuilder 手拼而不用 Arrays.toString：后者对 float 会输出多余的精度且带空格。
     */
    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("向量库不可用，请确认容器 pg-vec 运行中且已执行 pgvector_knowledge.sql");
        }
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
        }
    }

    /** 检索命中的一条分块 */
    public static class SearchHit {
        public long chunkId;
        public long docId;
        public int chunkIndex;
        public String content;
        public String title;
        public String fileName;
        public double score;
    }
}
