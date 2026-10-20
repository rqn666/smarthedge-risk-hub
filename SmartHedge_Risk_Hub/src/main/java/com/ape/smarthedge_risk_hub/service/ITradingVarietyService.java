package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;


public interface ITradingVarietyService {
    IPage<TradingVariety> findAllByPage(RequestParam requestParam);

    TradingVariety findById(int id);

   List<String> findByKeyWord(String keyWord);
}
