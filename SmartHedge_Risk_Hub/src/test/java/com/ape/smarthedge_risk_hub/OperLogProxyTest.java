package com.ape.smarthedge_risk_hub;

import com.ape.smarthedge_risk_hub.controller.FuturesContractTextController;
import com.ape.smarthedge_risk_hub.controller.SupplyController;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 钉死一个面试高频问题：操作日志的 AOP 到底用的哪种代理？
 *
 * <p>结论（本项目）：<b>CGLIB 子类代理</b>，不是 JDK 动态代理。两条理由，任意一条单独就足以成立：
 * <ol>
 *   <li>Spring Boot 2.x 起 {@code spring.aop.proxy-target-class} 默认就是 <b>true</b>
 *       （AopAutoConfiguration 里 matchIfMissing = true），application.yml 没有覆盖它。</li>
 *   <li>被切的三个 Controller 都是<b>不实现任何接口</b>的普通类；就算切回 JDK 模式，
 *       Spring 也会因为"没有接口可代理"自动退化成 CGLIB。</li>
 * </ol>
 *
 * <p>另一层容易被误会的点：依赖里有 aspectjweaver，但<b>这不是 AspectJ 静态织入</b>。
 * AspectJ 在这里只提供 @Aspect 注解和切点表达式的解析能力，
 * 真正的织入动作是 Spring AOP 在运行时用动态代理完成的（本项目即 CGLIB 生成子类）。
 * 要上真正的 AspectJ 织入得开 LTW（Load-Time Weaving）或 CTW，本项目都没开。
 */
@SpringBootTest
public class OperLogProxyTest {

    @Autowired
    private FuturesContractTextController textController;

    @Autowired
    private SupplyController supplyController;

    @Test
    public void 日志切面走的是CGLIB子类代理() {
        System.out.println("FuturesContractTextController 代理类 = " + textController.getClass().getName());
        System.out.println("SupplyController           代理类 = " + supplyController.getClass().getName());

        // 1. 确实被代理了（说明 @OperLog 的切面织入成功）
        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isAopProxy(textController),
                "Controller 应该被 AOP 代理，否则 @OperLog 不会生效");

        // 2. 是 CGLIB，不是 JDK 动态代理 —— 这是本题的答案
        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isCglibProxy(textController),
                "应为 CGLIB 代理（Boot 2.x 默认 proxy-target-class=true）");
        org.junit.jupiter.api.Assertions.assertFalse(AopUtils.isJdkDynamicProxy(textController),
                "不应是 JDK 动态代理");

        // 3. 类名里带 Spring 的 CGLIB 标记（...$$EnhancerBySpringCGLIB$$...），肉眼可验
        org.junit.jupiter.api.Assertions.assertTrue(
                textController.getClass().getName().contains("BySpringCGLIB"),
                "类名应包含 BySpringCGLIB，实际：" + textController.getClass().getName());

        // 4. 另一个 Controller 同样成立，排除"恰好这一个特殊"的偶然
        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isCglibProxy(supplyController),
                " SupplyController 也应是 CGLIB 代理");
    }
}
