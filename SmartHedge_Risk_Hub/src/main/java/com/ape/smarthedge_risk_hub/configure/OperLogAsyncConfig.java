package com.ape.smarthedge_risk_hub.configure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 操作日志专用线程池。
 *
 * <p>参数取向和 AI 线程池（aiExecutor）正好相反：日志任务是<b>短耗时、高吞吐</b>型，
 * 单次就是一次 insert，几毫秒完事，但并发量大 —— 每个写业务操作都来一条。
 * 所以核心线程给到 2、队列给到 500（允许瞬时分摊掉一波写操作），
 * 拒绝策略用 CallerRuns：队列真满了就退回业务线程同步写，比直接丢弃好 ——
 * 日志可以慢，但不要出现"操作发生了却没记录"的空洞。
 *
 * <p>关闭时等 10 秒收尾，避免停机瞬间丢掉最后一批日志。
 */
@Configuration
@EnableAsync
public class OperLogAsyncConfig {

    @Bean("operLogExecutor")
    public ThreadPoolTaskExecutor operLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("oper-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
