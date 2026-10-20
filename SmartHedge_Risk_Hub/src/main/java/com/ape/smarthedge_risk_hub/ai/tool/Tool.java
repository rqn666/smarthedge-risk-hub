package com.ape.smarthedge_risk_hub.ai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为大模型可调用的工具。
 * 启动时被 ToolRegistry 扫描并生成 JSON Schema 交给大模型。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /** 工具名，大模型用它来调用，全局唯一 */
    String name();

    /** 工具说明，写清楚「能查什么、什么场景用」，直接影响模型选择准确率 */
    String description();
}
