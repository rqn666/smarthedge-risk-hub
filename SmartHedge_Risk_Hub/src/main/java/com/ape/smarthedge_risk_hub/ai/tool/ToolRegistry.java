package com.ape.smarthedge_risk_hub.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心。启动后自动扫描所有 Spring Bean 中带 @Tool 的方法。
 * 用 BeanPostProcessor 而不是扫包，好处是拿到的就是 Spring 代理对象，AOP 依然生效。
 */
@Component
public class ToolRegistry implements BeanPostProcessor, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** name -> 工具元数据 */
    private final Map<String, ToolMeta> tools = new ConcurrentHashMap<String, ToolMeta>();

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }
            register(annotation, method, bean);
        }
        return bean;
    }

    private void register(Tool annotation, Method method, Object bean) {
        String name = annotation.name();
        if (tools.containsKey(name)) {
            throw new IllegalStateException("工具名重复: " + name + "（已存在 " + tools.get(name).method + "）");
        }
        ToolMeta meta = new ToolMeta();
        meta.name = name;
        meta.description = annotation.description();
        meta.method = method;
        meta.bean = bean;

        for (Parameter parameter : method.getParameters()) {
            ToolParam param = parameter.getAnnotation(ToolParam.class);
            if (param == null) {
                continue;
            }
            meta.params.put(parameter.getName(), param.value());
            if (param.required()) {
                meta.required.add(parameter.getName());
            }
        }
        tools.put(name, meta);
        log.info("[ai] 注册工具 {}({})", name, join(meta.params.keySet()));
    }

    private String join(java.util.Collection<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k);
        }
        return sb.toString();
    }

    public ToolMeta get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public List<ToolMeta> list() {
        return new ArrayList<ToolMeta>(tools.values());
    }

    public int size() {
        return tools.size();
    }

    /** 工具元数据 */
    public static class ToolMeta {
        private String name;
        private String description;
        private Method method;
        private Object bean;
        private final Map<String, String> params = new LinkedHashMap<String, String>();
        private final List<String> required = new ArrayList<String>();

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Method getMethod() {
            return method;
        }

        public Object getBean() {
            return bean;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public List<String> getRequired() {
            return required;
        }
    }
}
