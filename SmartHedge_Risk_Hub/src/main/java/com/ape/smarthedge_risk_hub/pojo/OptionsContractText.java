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
public class OptionsContractText {
    @TableId(type = IdType.AUTO)
    private int textId;
    private int optionId;
    private int varietyId;
    private int exchangeId;
    private String underlyingAsset;
    private String optionType;
    private String tradeUnit;
    private String quoteUnit;
    private double minPriceMove;
    private String limitRange;
    private String contractMonths;
    private String tradeTime;
    private String lastTradingDayRule;
    private String expiryDateRule;
    private String strikePriceRule;
    private String exerciseMethod;
    private String tradeCode;
    private Date updateTime;
    private Date createTime;
    @TableField(exist = false)
    private OptionsContract optionsContract;
    @TableField(exist = false)
    private TradingVariety tradingVariety;
    @TableField(exist = false)
    private Exchange exchange;
}
