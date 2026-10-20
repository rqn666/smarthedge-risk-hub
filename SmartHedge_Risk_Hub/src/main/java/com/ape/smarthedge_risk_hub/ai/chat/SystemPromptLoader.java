package com.ape.smarthedge_risk_hub.ai.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 加载 resources/prompt/system-prompt.txt。
 * 放在文件里，改人设不用改代码、不用重新打包逻辑。
 */
@Component
public class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);
    private static final String PATH = "prompt/system-prompt.txt";
    private static final String FALLBACK = "你是 SmartHedge 大宗商品智能问答助手，只回答大宗商品、套期保值与风险管理相关问题。";

    private volatile String cached;

    public String load() {
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            cached = read();
            return cached;
        }
    }

    /** 重新加载（改完提示词文件后调用 /api/ai/prompt/reload 即时生效） */
    public String reload() {
        cached = null;
        return load();
    }

    private String read() {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            ClassPathResource resource = new ClassPathResource(PATH);
            if (!resource.exists()) {
                log.warn("[ai] 未找到 {}，使用内置兜底人设", PATH);
                return FALLBACK;
            }
            reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            log.error("[ai] 读取系统提示词失败，使用内置兜底人设", e);
            return FALLBACK;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                }
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? FALLBACK : text;
    }
}
