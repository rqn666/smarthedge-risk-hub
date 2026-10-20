package com.ape.smarthedge_risk_hub.es.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ES 配置，对应 application.yml 的 es.* 段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "es")
public class EsProperties {

    /** ES 地址，多个逗号分隔 */
    private String hosts = "127.0.0.1:9200";

    /** 用户名密码（ES 未开安全认证时留空） */
    private String username = "";
    private String password = "";

    private int connectTimeoutMs = 5000;
    private int socketTimeoutMs = 30000;

    /** 查询默认返回条数 */
    private int defaultSize = 20;

    /** 是否启用 ES 搜索，false 时全部走数据库（便于回滚排障） */
    private boolean enabled = true;

    /** 双写失败补偿任务的开关与间隔 */
    private boolean retryEnabled = true;
    private int retryFixedDelayMs = 60000;
    private int retryMaxTimes = 5;
}
