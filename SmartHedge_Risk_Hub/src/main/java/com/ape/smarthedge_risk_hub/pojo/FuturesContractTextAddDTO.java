package com.ape.smarthedge_risk_hub.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class FuturesContractTextAddDTO {
    private int contractId;
    private String varietyName;
    private String varietyCode;
    private String tradeUnit;
    private String quoteUnit;
    private double minPriceMove;
    private String limitRange;
    private String contractMonths;
    private String tradeTime;
    private String lastTradingDayRule;
    private String deliveryDateRule;
    private String minMargin;
    private String exchangeName;
    private String industryName;

}
