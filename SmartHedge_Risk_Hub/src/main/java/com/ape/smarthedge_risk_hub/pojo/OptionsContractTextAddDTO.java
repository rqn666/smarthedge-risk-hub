package com.ape.smarthedge_risk_hub.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OptionsContractTextAddDTO {
    private int optionId;
    private String varietyName;
    private String exchangeName;
    private String industryName;
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
}
