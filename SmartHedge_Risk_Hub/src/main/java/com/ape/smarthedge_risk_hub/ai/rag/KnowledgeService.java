package com.ape.smarthedge_risk_hub.ai.rag;

import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库编排：上传 → 解析 → 分块 → 向量化 → 入库。
 *
 * 状态机：PENDING → PARSING → INDEXED / FAILED
 * 每一步失败都要落库，否则用户只会看到「传了但搜不到」，无从排查。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 单次上传大小上限：100MB（与 spring.servlet.multipart 配置呼应） */
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    @Autowired
    private AiProperties props;

    @Autowired
    private PgVectorStore store;

    @Autowired
    private DocumentParser parser;

    @Autowired
    private TextChunker chunker;

    @Autowired
    private EmbeddingClient embeddingClient;

    /** 索引结果 */
    public static class IndexResult {
        public long docId;
        public String status;
        public int pages;
        public int chunkCount;
        public String errorMsg;

        public boolean ok() {
            return "INDEXED".equals(status);
        }
    }

    // ==================== 上传 + 索引 ====================

    /**
     * 接收上传并建索引。文件会先落到磁盘（重建索引时不用重传），再走解析链路。
     */
    public IndexResult indexUpload(MultipartFile file, String title, String uploader) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new IllegalArgumentException("无法获取文件名");
        }
        originalName = sanitizeFileName(originalName);
        checkExtension(originalName);

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件超过上限 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
        }
        if (!store.isAvailable()) {
            throw new IllegalStateException("向量库不可用，无法建索引。"
                    + "请确认容器 pg-vec 在运行、库 vec_db 可连、且已执行 pgvector_knowledge.sql");
        }

        File saved = saveToDisk(file, originalName);
        String finalTitle = (title == null || title.trim().isEmpty())
                ? stripExtension(originalName) : title.trim();
        return doIndex(saved, originalName, finalTitle, uploader);
    }

    /** 重建：用已落地的文件重新解析向量化（改了分块参数后重跑） */
    public IndexResult reindex(long docId) {
        Map<String, Object> doc = store.getDocument(docId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        String path = (String) doc.get("filePath");
        if (path == null || !new File(path).isFile()) {
            throw new IllegalStateException("原始文件已不存在（" + path + "），请重新上传");
        }
        store.deleteChunks(docId);
        IndexResult r = doIndex(new File(path), (String) doc.get("fileName"),
                (String) doc.get("title"), null);
        r.docId = docId;
        return r;
    }

    /**
     * 核心索引流程。注意顺序：
     * 先写文档记录拿到 id → 再逐段落库 → 最后才置 INDEXED。
     * 中途任何异常都置 FAILED 并清掉已写入的分块，绝不留半成品。
     */
    private IndexResult doIndex(File file, String originalName, String title, String uploader) {
        IndexResult result = new IndexResult();
        long docId = store.createDocument(title, originalName, file.getAbsolutePath(),
                extensionOf(originalName), file.length(), uploader);
        result.docId = docId;

        try {
            store.updateStatus(docId, "PARSING", null, null);

            DocumentParser.ParsedDoc parsed = parser.parse(file, originalName);
            if (parsed.pages != null) {
                result.pages = parsed.pages;
            }

            List<String> chunks = chunker.chunk(parsed.text,
                    props.getRag().getChunkSize(), props.getRag().getChunkOverlap());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("解析后没有可索引的内容");
            }

            List<float[]> vectors = embeddingClient.embed(chunks);
            store.insertChunks(docId, chunks, vectors);
            store.updateStatus(docId, "INDEXED", chunks.size(), null);

            result.chunkCount = chunks.size();
            result.status = "INDEXED";
            log.info("[rag] 文档索引完成 docId={} title={} chunks={}", docId, title, chunks.size());
            return result;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 900) {
                msg = msg.substring(0, 900) + "...";
            }
            try {
                store.deleteChunks(docId);
                store.updateStatus(docId, "FAILED", 0, msg);
            } catch (Exception ignore) {
                // 状态回写失败不掩盖原始异常
            }
            log.warn("[rag] 文档索引失败 docId={} title={}: {}", docId, title, msg);
            result.status = "FAILED";
            result.errorMsg = msg;
            return result;
        }
    }

    // ==================== 检索 ====================

    /** 纯检索：返回命中的分块 */
    public List<PgVectorStore.SearchHit> search(String query, Integer topK) {
        if (!props.getRag().isEnabled()) {
            log.debug("[rag] 知识库检索已关闭（ai.rag.enabled=false）");
            return Collections.emptyList();
        }
        if (!store.isAvailable() || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            float[] qv = embeddingClient.embedOne(query.trim());
            double minScore = props.getRag().getMinScore();
            return store.search(qv, topK == null ? props.getRag().getTopK() : topK, minScore);
        } catch (Exception e) {
            // 检索失败要降级成「没查到」，而不是让整轮对话崩掉
            log.warn("[rag] 检索失败，降级为空结果: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 检索并拼成给大模型看的结构化结果（含文档名，供引用溯源） */
    public Map<String, Object> searchAsToolResult(String query, Integer topK) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("query", query);
        List<PgVectorStore.SearchHit> hits = search(query, topK);
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        List<String> sources = new ArrayList<String>();
        for (PgVectorStore.SearchHit hit : hits) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("document", hit.title);
            row.put("fileName", hit.fileName);
            row.put("chunkIndex", hit.chunkIndex);
            row.put("score", round(hit.score));
            row.put("content", hit.content);
            records.add(row);
            if (!sources.contains(hit.title)) {
                sources.add(hit.title);
            }
        }
        out.put("total", records.size());
        out.put("sources", sources);
        out.put("records", records);
        if (records.isEmpty()) {
            out.put("message", "知识库中未检索到相关内容。请勿编造依据，直接告知用户知识库暂无相关文档。");
        } else {
            out.put("message", "以上为知识库检索结果。回答时请在末尾用「参考：《文档名》」标注来源。");
        }
        return out;
    }

    // ==================== 文件落地 ====================

    /** 上传目录：优先配置，否则用工作目录下的 kb-uploads */
    public File resolveUploadDir() {
        String dir = props.getRag().getUploadDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + File.separator + "kb-uploads";
        }
        File f = new File(dir);
        if (!f.exists() && !f.mkdirs()) {
            log.warn("[rag] 上传目录创建失败: {}", f.getAbsolutePath());
        }
        return f;
    }

    private File saveToDisk(MultipartFile file, String originalName) {
        File dir = resolveUploadDir();
        File target = new File(dir, System.currentTimeMillis() + "_" + originalName);
        // 必须用 try-with-resources 关闭流：不关的话 Windows 会锁住 Tomcat 的临时上传文件，
        // 请求结束后 Tomcat 清理临时文件失败，日志里刷 UncheckedIOException: Cannot delete ...tmp
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败：" + e.getMessage(), e);
        }
        return target;
    }

    /**
     * 文件名消毒：只取最后一段，剥掉任何路径分隔符。
     * 不做这层的话，文件名里塞 "../" 就能把文件写到目录外面去。
     */
    private String sanitizeFileName(String name) {
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replace("..", "_").trim();
        if (n.isEmpty()) {
            n = "unnamed";
        }
        return n;
    }

    private void checkExtension(String fileName) {
        String ext = extensionOf(fileName);
        if (!DocumentParser.supportedExtensions().contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型 ." + ext
                    + "，仅支持：" + DocumentParser.supportedExtensions());
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
