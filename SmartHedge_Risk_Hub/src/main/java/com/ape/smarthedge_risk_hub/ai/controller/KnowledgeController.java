package com.ape.smarthedge_risk_hub.ai.controller;

import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.ape.smarthedge_risk_hub.ai.rag.KnowledgeService;
import com.ape.smarthedge_risk_hub.ai.rag.PgVectorStore;
import com.ape.smarthedge_risk_hub.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库（RAG）管理接口。
 *
 * 上传需要管理员权限 —— 鉴权（Shiro）在 M4 后续阶段接入；
 * 当前先保证功能链路可测，鉴权缺失是已知待办，不要在生产开着。
 */
@RestController
@RequestMapping("/api/ai/kb")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private PgVectorStore store;

    @Autowired
    private AiProperties props;

    /**
     * 上传文档并建索引。
     * POST /api/ai/kb/upload  form-data: file=@xxx.pdf, title=可选, uploader=可选
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "title", required = false) String title,
                                              @RequestParam(value = "uploader", required = false) String uploader) {
        try {
            KnowledgeService.IndexResult r = knowledgeService.indexUpload(file, title, uploader);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("docId", r.docId);
            data.put("status", r.status);
            data.put("pages", r.pages);
            data.put("chunkCount", r.chunkCount);
            data.put("errorMsg", r.errorMsg);
            if (!r.ok()) {
                return Result.fail(500, "索引失败：" + r.errorMsg + "（文档 id=" + r.docId + "，状态已置 FAILED）");
            }
            data.put("tip", "已入库 " + r.chunkCount + " 个分块，可直接用 /api/ai/kb/search 检索验证");
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("[rag] 上传失败", e);
            return Result.fail(500, e.getMessage());
        }
    }

    /**
     * 检索测试（不需要大模型，直接看向量召回效果）。
     * GET /api/ai/kb/search?query=保证金比例&topK=5
     */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam("query") String query,
                                              @RequestParam(value = "topK", required = false) Integer topK) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = knowledgeService.searchAsToolResult(query, topK);
        out.put("costMs", System.currentTimeMillis() - start);
        out.put("minScore", props.getRag().getMinScore());
        out.put("topK", topK == null ? props.getRag().getTopK() : topK);
        out.put("enabled", props.getRag().isEnabled());
        return Result.success(out);
    }

    /** 知识库状态：向量库连通性 + 文档/分块数量 + 当前参数 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("rag", store.stats());
        data.put("uploadDir", knowledgeService.resolveUploadDir().getAbsolutePath());
        data.put("supportedTypes", com.ape.smarthedge_risk_hub.ai.rag.DocumentParser.supportedExtensions());
        return Result.success(data);
    }

    /** 文档列表 */
    @GetMapping("/documents")
    public Result<List<Map<String, Object>>> documents() {
        return Result.success(store.listDocuments());
    }

    /** 单文档详情（含分块数与失败原因） */
    @GetMapping("/document")
    public Result<Map<String, Object>> document(@RequestParam("docId") long docId) {
        Map<String, Object> doc = store.getDocument(docId);
        if (doc == null) {
            return Result.fail(404, "文档不存在: " + docId);
        }
        return Result.success(doc);
    }

    /** 重建索引：用已落地文件重新解析向量化（调了分块参数后重跑） */
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(@RequestParam("docId") long docId) {
        try {
            KnowledgeService.IndexResult r = knowledgeService.reindex(docId);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("docId", docId);
            data.put("status", r.status);
            data.put("chunkCount", r.chunkCount);
            data.put("errorMsg", r.errorMsg);
            return r.ok() ? Result.success(data) : Result.fail(500, "重建失败：" + r.errorMsg);
        } catch (IllegalArgumentException e) {
            return Result.fail(404, e.getMessage());
        } catch (Exception e) {
            return Result.fail(500, e.getMessage());
        }
    }

    /** 删除文档（分块级联删除） */
    @DeleteMapping("/document")
    public Result<String> delete(@RequestParam("docId") long docId) {
        boolean ok = store.deleteDocument(docId);
        return ok ? Result.success("已删除文档 " + docId + " 及其全部分块")
                : Result.fail(404, "文档不存在: " + docId);
    }

    /** 上传目录自检：确认目录可写，避免上传到一半才发现权限问题 */
    @GetMapping("/upload-dir")
    public Result<Map<String, Object>> uploadDir() {
        File dir = knowledgeService.resolveUploadDir();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("path", dir.getAbsolutePath());
        data.put("exists", dir.exists());
        data.put("writable", dir.canWrite());
        data.put("maxFileSizeMB", KnowledgeService.MAX_FILE_SIZE / 1024 / 1024);
        List<String> files = new ArrayList<String>();
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                files.add(f.getName());
            }
        }
        data.put("fileCount", files.size());
        data.put("files", files);
        return Result.success(data);
    }
}
