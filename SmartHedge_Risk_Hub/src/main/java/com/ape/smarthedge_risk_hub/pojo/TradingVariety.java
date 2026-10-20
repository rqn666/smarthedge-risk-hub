package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradingVariety {
    @TableId(type = IdType.AUTO)
    private int varietyId;
    private String varietyName;
    private String varietyCode;
    private int exchangeId;
    private int industryId;
    @TableField(exist = false)
    private Industry industry;
    @TableField(exist = false)
    private Exchange exchange;
}
