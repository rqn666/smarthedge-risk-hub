package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FuturesContract {
    @TableId(type = IdType.AUTO)
    private int contractId;
    private String contractCode;
    private String contractName;
    private Date lastTradingDay;
    private int varietyId;
    private String updateMethod;
    private Date updateTime;
    private Date createTime;

    /**
     * 联表查出来的品种对象，不是 futures_contract 表的列。
     *
     * <p>必须标 exist=false：否则 MyBatis-Plus 用 BaseMapper 生成 SQL 时会把字段名
     * 按驼峰转下划线当成真实列，报 {@code Unknown column 'trading_variety' in 'field list'}。
     * 本项目其它实体（TradingVariety / FuturesContractText / OptionsContract 等）都标了，
     * 只有这个漏了 —— 因为原先的查询全走自定义 @Select，没触发过 BaseMapper 的自动 SQL。
     */
    @TableField(exist = false)
    private TradingVariety tradingVariety;
}
