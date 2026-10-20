package com.ape.smarthedge_risk_hub.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志标记。就两个属性，把它加在 Controller 方法上就行：
 *
 * <pre>
 * &#064;OperLog(module = "期货合约条款", action = "新增")
 * &#064;PostMapping("/insertText")
 * public Result insertText(...) { ... }
 * </pre>
 *
 * <p>一句话理解：这个注解就是一张<b>便利贴</b>，贴在方法上告诉切面
 * 「这个方法是『期货合约条款』模块的『新增』操作，给我记一条日志」。
 * 方法里面不用写任何日志代码。
 */
@Target(ElementType.METHOD)      // 只能加在方法上
@Retention(RetentionPolicy.RUNTIME)  // 运行时还能读到（切面要用反射读它）
public @interface OperLog {

    /** 业务模块，比如 "期货合约条款"、"供应商" */
    String module();

    /** 操作类型，比如 "新增"、"修改"、"删除" */
    
    String action();
}
