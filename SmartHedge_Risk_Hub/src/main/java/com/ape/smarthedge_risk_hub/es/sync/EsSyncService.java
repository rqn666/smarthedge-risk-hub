package com.ape.smarthedge_risk_hub.es.sync;

import com.ape.smarthedge_risk_hub.es.config.EsProperties;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsDocConverter;
import com.ape.smarthedge_risk_hub.es.core.EsDocumentService;
import com.ape.smarthedge_risk_hub.es.model.EsDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ES 双写服务：MySQL 写成功后把文档推到 ES，失败落重试表。
 *
 * 重要约定：
 * 1) 双写必须在 MySQL 事务提交之后调用。若放在 @Transactional 方法内部，一旦事务回滚，
 *    MySQL 回滚了而 ES 已经写入，就会出现"ES 有、库里没有"的脏数据。
 *    因此各业务 Service 采用 <b>先执行业务写，再用 TransactionSynchronization 的
 *    afterCommit 回调触发双写</b> 的方式（见 EsWriteHelper）。
 * 2) 任何 ES 异常都吞掉并落重试表，主业务绝不受影响。ES 是加速层，不是数据源。
 */
@Service
public class EsSyncService {

    private static final Logger log = LoggerFactory.getLogger(EsSyncService.class);

    @Autowired
    private EsDocumentService documentService;

    @Autowired
    private EsDocConverter docConverter;

    @Autowired
    private EsProperties props;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 写入或更新一条文档。entity 为业务 POJO（名称维度已填好）。
     */
    public void upsert(EsIndex index, Object entity) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            EsDoc doc = docConverter.fromEntity(index, entity);
            if (doc == null) {
                log.warn("[es] {} 转换文档为空，跳过双写", index.getIndexName());
                return;
            }
            documentService.save(index, doc);
            log.debug("[es] 双写成功 {}/{}", index.getIndexName(), doc.getId());
        } catch (Exception e) {
            log.warn("[es] 双写失败 {}: {}", index.getIndexName(), e.getMessage());
            // 此时可能连主键都没解析出来，只能靠 bizId 反查，因此按实体尽力取 id
            recordRetry(index, extractId(index, entity), "UPSERT", e.getMessage());
        }
    }

    /**
     * 按主键删除一条文档。失败不影响主业务。
     */
    public void delete(EsIndex index, Object id) {
        if (!props.isEnabled() || id == null) {
            return;
        }
        try {
            documentService.deleteById(index, id);
            log.debug("[es] 删除文档成功 {}/{}", index.getIndexName(), id);
        } catch (Exception e) {
            log.warn("[es] 删除文档失败 {}/{}, 已记录重试: {}", index.getIndexName(), id, e.getMessage());
            recordRetry(index, String.valueOf(id), "DELETE", e.getMessage());
        }
    }

    /**
     * 尽力从实体里取主键值（反射 getter），取不到就返回 null。
     * 用于文档转换失败时仍能落一条重试记录。
     */
    private String extractId(EsIndex index, Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            String getter = "get" + Character.toUpperCase(index.getIdField().charAt(0))
                    + index.getIdField().substring(1);
            Object value = entity.getClass().getMethod(getter).invoke(entity);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 落一条重试记录 */
    private void recordRetry(EsIndex index, String bizId, String opType, String errorMsg) {
        if (bizId == null) {
            log.error("[es] {} 双写失败且拿不到主键，无法落重试表，需人工介入", index.getIndexName());
            return;
        }
        try {
            jdbcTemplate.update(
                    "insert into es_retry_task(index_name, table_name, biz_id, op_type, retry_count, max_retry, status, error_msg, create_time, update_time) "
                            + "values (?, ?, ?, ?, 0, ?, 'PENDING', ?, now(), now())",
                    index.getIndexName(), index.getTableName(), bizId, opType,
                    props.getRetryMaxTimes(), truncate(errorMsg));
            log.info("[es] 已落重试任务 {}/{} op={}", index.getIndexName(), bizId, opType);
        } catch (Exception e) {
            log.error("[es] 记录重试任务也失败了，请检查 es_retry_task 表是否已创建: {}", e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 查询待重试任务（给补偿任务用） */
    public List<EsRetryTask> findPending(int limit) {
        return jdbcTemplate.query(
                "select * from es_retry_task where status = 'PENDING' order by id asc limit ?",
                new Object[]{limit},
                (rs, rowNum) -> {
                    EsRetryTask task = new EsRetryTask();
                    task.setId(rs.getLong("id"));
                    task.setIndexName(rs.getString("index_name"));
                    task.setTableName(rs.getString("table_name"));
                    task.setBizId(rs.getString("biz_id"));
                    task.setOpType(rs.getString("op_type"));
                    task.setRetryCount(rs.getInt("retry_count"));
                    task.setMaxRetry(rs.getInt("max_retry"));
                    task.setStatus(rs.getString("status"));
                    task.setErrorMsg(rs.getString("error_msg"));
                    task.setCreateTime(rs.getTimestamp("create_time"));
                    task.setUpdateTime(rs.getTimestamp("update_time"));
                    return task;
                });
    }

    public void markSuccess(Long id) {
        jdbcTemplate.update("update es_retry_task set status='SUCCESS', update_time=now() where id=?", id);
    }

    public void markRetry(Long id, int retryCount, int maxRetry, String errorMsg) {
        String status = retryCount >= maxRetry ? "DEAD" : "PENDING";
        jdbcTemplate.update(
                "update es_retry_task set retry_count=?, status=?, error_msg=?, update_time=now() where id=?",
                retryCount, status, truncate(errorMsg), id);
    }

    /** 统计各状态任务数，给监控/排障用 */
    public Map<String, Object> stats() {
        Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        for (String status : new String[]{"PENDING", "SUCCESS", "DEAD"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from es_retry_task where status=?", Integer.class, status);
            map.put(status, count == null ? 0 : count);
        }
        return map;
    }

    /**
     * 按表名与主键从 MySQL 反查最新数据。
     * 注意：这里只查本表，不含联表补名。补偿重建文档时统一走
     * EsFullSyncService.loadOne(index, bizId)，那条 SQL 才有 join。
     */
    public Map<String, Object> loadFromMysql(EsIndex index, String bizId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select * from " + index.getTableName() + " where " + index.getIdColumn() + " = ?",
                    bizId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[es] 从 MySQL 反查数据失败 {}/{}: {}", index.getTableName(), bizId, e.getMessage());
            return null;
        }
    }
}
