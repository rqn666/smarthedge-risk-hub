package com.ape.smarthedge_risk_hub.es.sync;

import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 双写入口的薄封装，解决"事务提交后才写 ES"的问题。
 *
 * 场景：业务方法标了 @Transactional，方法体最后调用 upsert。
 * 如果直接写 ES，此刻 MySQL 尚未提交（可能回滚），就会产生
 * "ES 有数据、MySQL 没有" 的脏数据。
 *
 * 解法：判断当前是否在事务中
 *  - 在事务中 → 注册 afterCommit 回调，事务真提交了才写 ES
 *  - 不在事务中 → 立即写
 */
@Component
public class EsWriteHelper {

    private static final Logger log = LoggerFactory.getLogger(EsWriteHelper.class);

    @Autowired
    private EsSyncService syncService;

    /** 业务写成功后调用：把实体同步到 ES */
    public void upsertAfterCommit(final EsIndex index, final Object entity) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncService.upsert(index, entity);
                    } catch (Exception e) {
                        // 双写里已经吞过异常了，这里兜底保证不把异常抛回事务框架
                        log.error("[es] afterCommit 双写异常 {}: {}", index.getIndexName(), e.getMessage());
                    }
                }
            });
        } else {
            syncService.upsert(index, entity);
        }
    }

    /** 业务删除成功后调用：把文档从 ES 移除 */
    public void deleteAfterCommit(final EsIndex index, final Object id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        syncService.delete(index, id);
                    } catch (Exception e) {
                        log.error("[es] afterCommit 删除异常 {}: {}", index.getIndexName(), e.getMessage());
                    }
                }
            });
        } else {
            syncService.delete(index, id);
        }
    }
}
