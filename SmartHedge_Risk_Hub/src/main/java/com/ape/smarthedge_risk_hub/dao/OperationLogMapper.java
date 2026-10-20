package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.OperationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 操作日志持久化。
 *
 * <p>只用 BaseMapper 的 insert，不接 ServiceImpl，也没配 ES 双写 ——
 * 日志是审计数据，不是业务数据，不进搜索索引。
 */
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
