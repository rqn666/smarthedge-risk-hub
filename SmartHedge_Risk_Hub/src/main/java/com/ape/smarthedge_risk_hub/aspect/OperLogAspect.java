package com.ape.smarthedge_risk_hub.aspect;

import com.ape.smarthedge_risk_hub.annotation.OperLog;
import com.ape.smarthedge_risk_hub.pojo.OperationLog;
import com.ape.smarthedge_risk_hub.service.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志切面（Spring AOP，底层由 CGLIB 生成代理子类）。
 *
 * <p><b>整个类要懂的只有下面 doLog 这 20 行</b>，从上往下读一遍就明白在干什么：
 * 进来先记下时间和请求信息 → 放行让业务方法执行 → 不管成功失败都记一条日志 → 完事。
 * 后面那些 private 方法全是打杂的（取 IP、转 JSON），不影响理解。
 *
 * <p>为什么用 @Around（环绕）而不是 @Before + @AfterReturning + @AfterThrowing 三个通知拼：
 * 开始时间只能存在一个地方，用三个通知就得存成成员变量，而切面是单例，
 * 并发时 A 请求的开始时间会被 B 请求覆盖，耗时全乱。环绕通知用方法内的局部变量，天生不会串。
 */
@Aspect
@Component
public class OperLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    @Autowired
    private OperationLogService logService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 主流程：就这一段是要懂的 ====================

    /**
     * 拦住所有加了 @OperLog 的方法。
     *
     * <p>pjp.proceed() 是分界线：<b>它上面是"方法执行前"，它下面是"方法执行后"</b>。
     */
    @Around("@annotation(operLog)")
    public Object doLog(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {

        // ① 方法执行前：先把要记的东西准备好
        HttpServletRequest request = currentRequest();   // 当前这次 HTTP 请求
        String ip = clientIp(request);                   // 客户端 IP
        String params = paramsToJson(pjp.getArgs());     // 方法入参转成 JSON
        long start = System.currentTimeMillis();         // 开始计时

        try {
            // ② 执行真正的业务方法（这一行就等于原来那个 Controller 方法本身）
            Object result = pjp.proceed();

            // ③ 没抛异常 = 成功 → 异步记一条成功日志（丢给线程池，不等它写完）
            logService.saveAsync(buildLog(operLog, pjp, request, ip, params, start, 1, null));
            return result;

        } catch (Throwable e) {
            // ④ 抛异常 = 失败 → 记一条失败日志，把异常原因也存下来
            logService.saveAsync(buildLog(operLog, pjp, request, ip, params, start, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));

            // ⑤ 关键：异常必须原样抛回去！
            //    这里 catch 只是为了记录，绝不能把异常吞掉 ——
            //    吞掉以后前端收到的是"操作成功"，实际数据却是失败的，这是最坑的 bug。
            throw e;
        }
    }

    // ==================== 下面是打杂的小方法，不用背 ====================

    /** 把要存的字段填进日志对象 */
    private OperationLog buildLog(OperLog operLog, ProceedingJoinPoint pjp, HttpServletRequest request,
                                  String ip, String params, long start, int success, String errorMsg) {
        OperationLog r = new OperationLog();
        r.setModule(operLog.module());    // 模块名，来自注解
        r.setAction(operLog.action());    // 动作名，来自注解
        // 方法名，比如 FuturesContractTextController.insertText
        r.setMethod(pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName());
        r.setParams(params);
        r.setIp(ip);
        r.setCostMs(System.currentTimeMillis() - start);   // 耗时 = 现在 - 开始
        r.setSuccess(success);                              // 1 成功 / 0 失败
        r.setErrorMsg(errorMsg);                            // 失败原因，成功时是 null
        // 操作人：本项目还没做登录，先固定 anonymous；以后接了鉴权从 request 里取用户名即可
        r.setUsername(request == null ? "anonymous" : usernameOf(request));
        return r;
    }

    /** 拿到当前这次 HTTP 请求（从 Spring 的请求上下文里取） */
    private HttpServletRequest currentRequest() {
        try {
            return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        } catch (Exception e) {
            return null;   // 非 Web 请求场景（比如定时任务）没有请求对象
        }
    }

    /**
     * 取客户端 IP。
     *
     * <p>为什么不能只写 request.getRemoteAddr()：应用一旦挂在 Nginx 后面，
     * 拿到的是 Nginx 的 IP（所有人都是 127.0.0.1），审计就废了。
     * 所以优先读 X-Forwarded-For —— 这个头记录请求经过的每一跳，第一个才是真实用户 IP。
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    private String usernameOf(HttpServletRequest request) {
        String header = request.getHeader("X-Username");
        return (header == null || header.trim().isEmpty()) ? "anonymous" : header.trim();
    }

    /**
     * 把方法入参转成 JSON 字符串存下来。
     *
     * <p>两个保护：① request / response / 文件流这些对象序列化会炸或超大，跳过；
     * ② 整体 try-catch，万一序列化失败也只记一句"参数记录失败"，绝不能影响业务。
     */
    private String paramsToJson(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> safe = new ArrayList<Object>();
        for (Object arg : args) {
            if (arg == null || skippable(arg)) {
                continue;
            }
            safe.add(arg);
        }
        if (safe.isEmpty()) {
            return null;
        }
        try {
            String json = mapper.writeValueAsString(safe.size() == 1 ? safe.get(0) : safe);
            return json.length() > 2000 ? json.substring(0, 2000) + "..." : json;
        } catch (Exception e) {
            return "[参数序列化失败]";
        }
    }

    private boolean skippable(Object arg) {
        return arg instanceof javax.servlet.ServletRequest
                || arg instanceof javax.servlet.ServletResponse
                || arg instanceof java.io.InputStream
                || arg instanceof java.io.OutputStream
                || arg instanceof org.springframework.web.multipart.MultipartFile;
    }
}
