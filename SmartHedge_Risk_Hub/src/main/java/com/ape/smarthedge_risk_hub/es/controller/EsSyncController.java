package com.ape.smarthedge_risk_hub.es.controller;

import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsDocumentService;
import com.ape.smarthedge_risk_hub.es.core.EsIndexManager;
import com.ape.smarthedge_risk_hub.es.sync.EsFullSyncService;
import com.ape.smarthedge_risk_hub.es.sync.EsRetryScheduler;
import com.ape.smarthedge_risk_hub.es.sync.EsSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 运维接口：全量同步、重建索引、补偿触发、状态查看。
 *
 * 注意：这些接口会做重活（全量同步可能几十秒），线上应加管理员鉴权。
 * 当前项目鉴权用 Shiro，等 Shiro 模块接入后把这些路径纳入拦截即可。
 */
@RestController
@RequestMapping("/api/es")
public class EsSyncController {

    private static final Logger log = LoggerFactory.getLogger(EsSyncController.class);

    @Autowired
    private EsFullSyncService fullSyncService;

    @Autowired
    private EsIndexManager indexManager;

    @Autowired
    private EsDocumentService documentService;

    @Autowired
    private EsSyncService syncService;

    @Autowired
    private EsRetryScheduler retryScheduler;

    /**
     * 全量同步（手动触发一次即可）。
     * POST /api/es/sync/full?recreate=false
     *
     * recreate=true 会先删索引再重建，用于彻底修复数据漂移；
     * 默认 false，直接覆盖写入，不中断服务。
     */
    @PostMapping("/sync/full")
    public Result<List<Map<String, Object>>> fullSync(
            @RequestParam(required = false, defaultValue = "false") boolean recreate) {
        log.info("[es] 收到全量同步请求, recreate={}", recreate);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = fullSyncService.syncAll(recreate);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int ok = 0;
        int totalDocs = 0;
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("success"))) {
                ok++;
            }
            Object es = r.get("esDocs");
            if (es instanceof Number) {
                totalDocs += ((Number) es).longValue();
            }
        }
        summary.put("indexCount", results.size());
        summary.put("successCount", ok);
        summary.put("totalDocs", totalDocs);
        summary.put("costMs", cost);
        summary.put("details", results);
        log.info("[es] 全量同步结束: {}/{} 个索引成功, 共 {} 条文档, 耗时 {}ms",
                ok, results.size(), totalDocs, cost);
        if (ok <= 0) {
            return Result.fail(503, "全部索引同步失败，请检查 ES 是否可用: " + summary);
        }
        return Result.success(results);
    }

    /** 只同步单个索引。POST /api/es/sync/index?index=futures_contract_text&recreate=false */
    @PostMapping("/sync/index")
    public Result<Map<String, Object>> syncOne(@RequestParam String index,
                                               @RequestParam(required = false, defaultValue = "false") boolean recreate) {
        EsIndex esIndex = EsIndex.ofIndexName(index);
        if (esIndex == null) {
            return Result.fail(400, "未知索引: " + index);
        }
        Map<String, Object> result = fullSyncService.syncIndex(esIndex, recreate);
        return Boolean.TRUE.equals(result.get("success"))
                ? Result.success(result)
                : Result.fail(503, String.valueOf(result.get("error")));
    }

    /** 手动触发一次补偿重放（不用等定时任务） POST /api/es/retry/run */
    @PostMapping("/retry/run")
    public Result<String> runRetry() {
        retryScheduler.compensate();
        return Result.success("补偿已执行，详见服务端日志");
    }

    /** 查看双写失败任务统计 GET /api/es/retry/stats */
    @GetMapping("/retry/stats")
    public Result<Map<String, Object>> retryStats() {
        try {
            return Result.success(syncService.stats());
        } catch (Exception e) {
            return Result.fail(503, "查询失败（es_retry_task 表可能未创建）: " + e.getMessage());
        }
    }

    /** 查看各索引状态：文档数、是否存在 GET /api/es/status */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (EsIndex index : EsIndex.all()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", index.getIndexName());
            item.put("table", index.getTableName());
            item.put("label", index.getLabel());
            try {
                item.put("exists", indexManager.exists(index));
                item.put("docs", documentService.count(index));
            } catch (Exception e) {
                item.put("exists", false);
                item.put("error", e.getMessage());
            }
            list.add(item);
        }
        data.put("indexes", list);
        try {
            data.put("retryTasks", syncService.stats());
        } catch (Exception e) {
            data.put("retryTasks", "查询失败: " + e.getMessage());
        }
        return Result.success(data);
    }

    /**
     * 删除索引（危险操作，仅用于重建前的清理）。
     * DELETE 语义但用 POST 暴露，避免被爬虫/预取误触发。
     */
    @PostMapping("/index/delete")
    public Result<String> deleteIndex(@RequestParam String index) {
        EsIndex esIndex = EsIndex.ofIndexName(index);
        if (esIndex == null) {
            return Result.fail(400, "未知索引: " + index);
        }
        boolean deleted = indexManager.delete(esIndex);
        return Result.success(deleted ? "已删除 " + index : index + " 不存在");
    }
}
