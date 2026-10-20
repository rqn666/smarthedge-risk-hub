package com.ape.smarthedge_risk_hub.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具执行器：按名字+参数反射调用工具方法。
 * 异常一律兜成可读文本回灌给大模型，让它自己改参数重试，而不是直接 500。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    @Autowired
    private ToolRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    public String execute(String toolName, Map<String, String> args) {
        ToolRegistry.ToolMeta meta = registry.get(toolName);
        if (meta == null) {
            log.warn("[ai] 未知工具: {}", toolName);
            return "错误：不存在名为 " + toolName + " 的工具，请从可用工具列表中选择。";
        }
        long start = System.currentTimeMillis();
        try {
            Object[] invokeArgs = buildArgs(meta, args);
            Object result = meta.getMethod().invoke(meta.getBean(), invokeArgs);
            String text = result == null ? "{\"message\":\"未查询到数据\"}" : toJson(result);
            log.info("[ai] 工具 {} 执行完成, cost={}ms, args={}", toolName, System.currentTimeMillis() - start, args);
            return text;
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("[ai] 工具 {} 执行失败: {}", toolName, cause.getMessage());
            return "错误：调用工具 " + toolName + " 失败（" + cause.getMessage() + "），请检查参数后重试。";
        }
    }

    private Object[] buildArgs(ToolRegistry.ToolMeta meta, Map<String, String> args) {
        Parameter[] parameters = meta.getMethod().getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String key = annotation == null ? parameter.getName() : parameter.getName();
            String raw = args == null ? null : args.get(key);
            values[i] = convert(raw, parameter.getType());
        }
        return values;
    }

    /** 模型给的都是字符串，按目标类型转换；空串按 null 处理 */
    private Object convert(String raw, Class<?> type) {
        if (raw == null || raw.trim().isEmpty()) {
            if (type.isPrimitive()) {
                if (type == boolean.class) {
                    return Boolean.FALSE;
                }
                if (type == double.class) {
                    return 0.0d;
                }
                if (type == float.class) {
                    return 0.0f;
                }
                if (type == long.class) {
                    return 0L;
                }
                return 0;
            }
            return null;
        }
        String value = raw.trim();
        if (type == String.class || type == Object.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        }
        return value;
    }

    private String toJson(Object result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    /** 把工具列表转成 LlmClient 需要的定义 */
    public List<com.ape.smarthedge_risk_hub.ai.client.LlmClient.ToolDef> asToolDefs() {
        List<com.ape.smarthedge_risk_hub.ai.client.LlmClient.ToolDef> defs =
                new ArrayList<com.ape.smarthedge_risk_hub.ai.client.LlmClient.ToolDef>();
        for (ToolRegistry.ToolMeta meta : registry.list()) {
            com.ape.smarthedge_risk_hub.ai.client.LlmClient.ToolDef def =
                    new com.ape.smarthedge_risk_hub.ai.client.LlmClient.ToolDef(meta.getName(), meta.getDescription());
            for (Map.Entry<String, String> e : meta.getParams().entrySet()) {
                def.param(e.getKey(), e.getValue());
            }
            for (String r : meta.getRequired()) {
                def.required(r);
            }
            defs.add(def);
        }
        return defs;
    }
}
