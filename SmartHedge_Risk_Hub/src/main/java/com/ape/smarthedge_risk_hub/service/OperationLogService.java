package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.dao.OperationLogMapper;
import com.ape.smarthedge_risk_hub.pojo.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 操作日志落库。
 *
 * <p><b>核心约定：日志写入失败绝不影响主业务。</b>
 * 落库整段包在 try-catch 里，异常只打日志不往外抛 —— 审计功能是附属能力，
 * 不能因为日志表写满、连接超时就让"新增合约条款"这种主流程失败。
 *
 * <p>走 {@code @Async("operLogExecutor")} 独立线程池：
 * 一次入库几毫秒，量却很大（每个写操作一条）。放进业务线程会白等一个 RTT，
 * 放进 Tomcat 工作线程则在大批量操作时挤占请求处理能力。独立线程池既不拖慢接口，
 * 也把日志的失败隔离在自己的线程里。
 *
 * <p>注意别踩两个坑：
 * <ol>
 *   <li>@Async 方法<b>必须</b>能被 Spring AOP 代理 —— 同类内部调用（this.saveAsync）不生效，
 *       会退化成同步执行。所以切面里注入的是本 Service 的 Bean，而不是 new 一个。</li>
 *   <li>@Async 方法返回值只能是 void 或 Future，不能返回 boolean 之类的基本类型，
 *       否则调用方拿到的是 null。</li>
 * </ol>
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    @Autowired
    private OperationLogMapper operationLogMapper;

    /** 异步落库。失败只记 warn，不抛异常 */
    @Async("operLogExecutor")
    public void saveAsync(OperationLog record) {
        if (record == null) {
            return;
        }
        try {
            operationLogMapper.insert(record);
        } catch (Exception e) {
            // 日志写失败不回传业务线程：打 warn 足够，运维能从这里发现问题
            log.warn("[operlog] 操作日志写入失败 module={} action={} method={}: {}",
                    record.getModule(), record.getAction(), record.getMethod(), e.getMessage());
        }
    }
}
