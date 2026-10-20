-- ============================================================
-- ES 双写失败重试表
-- 用途：MySQL 写成功后同步写 ES 失败时，落一条记录，由定时任务补偿重放。
-- 执行：在 smarthedge_risk_hub 库中执行一次即可。
-- ============================================================

CREATE TABLE IF NOT EXISTS `es_retry_task` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `index_name`  VARCHAR(64)  NOT NULL COMMENT '目标 ES 索引名',
  `table_name`  VARCHAR(64)  NOT NULL COMMENT '对应 MySQL 表名',
  `biz_id`      VARCHAR(64)  NOT NULL COMMENT '业务主键值（同时是 ES 文档 _id）',
  `op_type`     VARCHAR(16)  NOT NULL COMMENT '操作类型：UPSERT / DELETE',
  `retry_count` INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `max_retry`   INT          NOT NULL DEFAULT 5 COMMENT '最大重试次数，超过后标记 DEAD',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / SUCCESS / DEAD',
  `error_msg`   VARCHAR(500)          DEFAULT NULL COMMENT '最后一次失败原因',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_index_biz` (`index_name`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ES 双写失败重试任务';

-- ------------------------------------------------------------
-- 排障常用查询
-- ------------------------------------------------------------
-- 待重试任务：
--   SELECT * FROM es_retry_task WHERE status = 'PENDING' ORDER BY id ASC;
-- 重试耗尽需人工介入：
--   SELECT * FROM es_retry_task WHERE status = 'DEAD' ORDER BY update_time DESC;
-- 各状态统计：
--   SELECT status, COUNT(*) FROM es_retry_task GROUP BY status;
-- 重置某条失败任务让它再试一次：
--   UPDATE es_retry_task SET status='PENDING', retry_count=0, error_msg=NULL WHERE id = ?;
