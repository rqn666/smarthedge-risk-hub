package com.ape.smarthedge_risk_hub.es.sync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 双写失败重试记录。ES 写失败时落一条，由定时任务补偿。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EsRetryTask {

    private Long id;
    /** 目标 ES 索引名 */
    private String indexName;
    /** 对应 MySQL 表名 */
    private String tableName;
    /** 业务主键值 */
    private String bizId;
    /** UPSERT 或 DELETE */
    private String opType;
    /** 已重试次数 */
    private Integer retryCount;
    /** 最大重试次数，超过后标记 DEAD 不再重试 */
    private Integer maxRetry;
    /** PENDING / SUCCESS / DEAD */
    private String status;
    /** 最后一次失败原因 */
    private String errorMsg;
    private Date createTime;
    private Date updateTime;
}
