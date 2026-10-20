package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 操作日志（表 sys_oper_log，库里已存在，字段按现有结构对齐）。
 *
 * <p>成功/失败用 {@code success} 记（1 成功、0 失败），失败原因落 {@code errorMsg}。
 * 不存"响应结果"——接口返回体可能很长而且含业务数据，记参数+成败+耗时足够复盘定位，
 * 全量记返回体既没必要又会撑大表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_oper_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long logId;

    /** 操作人。本项目暂无登录态，默认 anonymous；接入鉴权后从会话/令牌里取 */
    private String username;

    /** 业务模块，取自 @OperLog.module */
    private String module;

    /** 操作类型，取自 @OperLog.action */
    private String action;

    /** 目标方法，格式 Controller 简类名.方法名 */
    private String method;

    /** 请求参数（JSON），超长截断至 2000 字符；request/response/文件流等对象自动跳过不记 */
    private String params;

    /** 客户端 IP。反向代理后要走 X-Forwarded-For 才拿得到真实 IP */
    private String ip;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 1 成功 / 0 失败（抛异常即失败） */
    private Integer success;

    /** 失败原因（异常 message），超长截断 */
    private String errorMsg;

    /** 操作时间，数据库默认 CURRENT_TIMESTAMP，插入时不传 */
    private Date createTime;
}
