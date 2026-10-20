package com.ape.smarthedge_risk_hub.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 对话的专用线程池。
 *
 * <p>为什么必须隔离出来：SSE 流式对话在服务端要一直持有一个线程直到大模型把答案吐完，
 * 本地 7B 模型单次可能跑几十秒。如果直接占用 Tomcat 的工作线程（默认 200），
 * 少量并发问答就能把整个 Web 容器拖到无法响应其他请求。
 *
 * <p>参数取向：AI 调用是<b>长耗时、低并发</b>型任务，所以核心线程少、队列偏小、
 * 拒绝策略用 CallerRuns —— 队列满时退回调用线程执行，宁可慢也不丢请求。
 * 注意<b>不要</b>用 newFixedThreadPool 那种无界队列，积压几十个请求会把内存吃光
 * （每个请求都挂着一份完整对话上下文）。
 */
@Configuration
@EnableAsync
public class AiAsyncConfig {

    @Bean("aiExecutor")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等在跑的对话收尾，避免出现半截回答
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
