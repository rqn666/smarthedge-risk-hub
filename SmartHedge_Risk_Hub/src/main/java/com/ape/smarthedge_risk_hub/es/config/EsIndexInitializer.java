package com.ape.smarthedge_risk_hub.es.config;

import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时确保所有 ES 索引存在。
 *
 * 关键设计：ES 连不上绝不阻塞应用启动。
 * ES 在本项目里是"加速层"，不是数据源；ES 挂了业务应该照常跑（走数据库）。
 * 所以这里捕获所有异常只打日志，不抛出。
 */
@Component
public class EsIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EsIndexInitializer.class);

    @Autowired
    private EsIndexManager indexManager;

    @Autowired
    private EsProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.warn("[es] es.enabled=false，跳过索引初始化");
            return;
        }
        log.info("[es] 开始检查/初始化索引, hosts={}", props.getHosts());
        int created = 0;
        int exists = 0;
        for (EsIndex index : EsIndex.all()) {
            try {
                if (indexManager.createIfAbsent(index)) {
                    created++;
                } else {
                    exists++;
                }
            } catch (Exception e) {
                log.error("[es] 索引 {} 初始化失败（不影响应用启动，搜索会降级走数据库）: {}",
                        index.getIndexName(), e.getMessage());
            }
        }
        log.info("[es] 索引初始化完成: 新建 {} 个, 已存在 {} 个", created, exists);
    }
}
