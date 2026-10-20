package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import javax.validation.Valid;
import java.util.Date;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OptionsContract {
    @TableId(value = "option_id", type = IdType.AUTO)
    private int optionId;
    private String optionCode;
    private String optionName;
    private int underlyingContractId;
    private String optionType;
    private double strikePrice;
    private Date lastTradingDay;
    private int varietyId;
    private String updateMethod;
    private Date updateTime;
    private Date createTime;
    @TableField(exist = false)
    private TradingVariety tradingVariety;
}
