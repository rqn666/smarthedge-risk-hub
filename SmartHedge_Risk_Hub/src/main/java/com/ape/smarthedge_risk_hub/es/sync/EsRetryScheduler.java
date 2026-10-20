package com.ape.smarthedge_risk_hub.es.sync;

import com.ape.smarthedge_risk_hub.es.config.EsProperties;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsDocumentService;
import com.ape.smarthedge_risk_hub.es.model.EsDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 双写失败的补偿任务。
 *
 * 处理逻辑：
 *  - 扫 es_retry_task 里 PENDING 的任务
 *  - UPSERT：从 MySQL 反查最新数据（含 join 补名）重建文档写入 ES；
 *           若 MySQL 里已经没有这条数据，说明业务上已删除 → 改为删 ES 文档（自愈）
 *  - DELETE：直接从 ES 删除；数据不存在也算成功（幂等）
 *  - 失败则 retry_count + 1，超过 max_retry 标记 DEAD 不再重试，等人工介入
 *
 * 为什么补偿时要从 MySQL 反查而不是重放原文档？
 * 因为失败后数据可能已经被再次修改，反查能拿到"当前最新"，避免把旧版本写回 ES。
 */
@Component
public class EsRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EsRetryScheduler.class);

    /** 单次最多处理多少条，防止卡太久 */
    private static final int BATCH_LIMIT = 100;

    @Autowired
    private EsSyncService syncService;

    @Autowired
    private EsDocumentService documentService;

    @Autowired
    private EsFullSyncService fullSyncService;

    @Autowired
    private EsProperties props;

    @Scheduled(fixedDelayString = "${es.retry-fixed-delay-ms:60000}", initialDelay = 30000)
    public void compensate() {
        if (!props.isEnabled() || !props.isRetryEnabled()) {
            return;
        }
        List<EsRetryTask> tasks;
        try {
            tasks = syncService.findPending(BATCH_LIMIT);
        } catch (Exception e) {
            log.warn("[es] 查询待重试任务失败（es_retry_task 表可能未创建）: {}", e.getMessage());
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        log.info("[es] 开始补偿 {} 条双写失败任务", tasks.size());
        int ok = 0;
        int fail = 0;
        for (EsRetryTask task : tasks) {
            if (replay(task)) {
                ok++;
            } else {
                fail++;
            }
        }
        log.info("[es] 补偿结束: 成功 {} 条, 失败 {} 条", ok, fail);
    }

    /** 重放单条任务，返回是否成功 */
    private boolean replay(EsRetryTask task) {
        EsIndex index = EsIndex.ofIndexName(task.getIndexName());
        if (index == null) {
            syncService.markRetry(task.getId(), task.getRetryCount() + 1, task.getMaxRetry(),
                    "未找到索引定义: " + task.getIndexName());
            return false;
        }
        try {
            if ("DELETE".equalsIgnoreCase(task.getOpType())) {
                documentService.deleteById(index, task.getBizId());
                syncService.markSuccess(task.getId());
                return true;
            }
            // UPSERT：反查最新数据重建文档
            EsDoc doc = fullSyncService.loadOne(index, task.getBizId());
            if (doc == null || doc.isEmpty()) {
                // 库里已经没了，说明业务上删除了，ES 也该删掉，属于自愈
                documentService.deleteById(index, task.getBizId());
                syncService.markSuccess(task.getId());
                log.info("[es] 补偿 {}#{} 时 MySQL 已无此数据，转为删除 ES 文档", index.getIndexName(), task.getBizId());
                return true;
            }
            documentService.save(index, doc);
            syncService.markSuccess(task.getId());
            return true;
        } catch (Exception e) {
            int next = task.getRetryCount() + 1;
            log.warn("[es] 补偿失败 {}/{} 第 {} 次: {}", task.getIndexName(), task.getBizId(), next, e.getMessage());
            syncService.markRetry(task.getId(), next, task.getMaxRetry(), e.getMessage());
            return false;
        }
    }
}
