package com.ape.smarthedge_risk_hub.ai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具方法的参数，用于生成 JSON Schema 与提示词中的参数说明。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /** 参数说明，写清示例值，模型更容易填对 */
    String value();

    /** 是否必填，默认必填 */
    boolean required() default true;
}
