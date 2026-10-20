package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import com.ape.smarthedge_risk_hub.service.ITradingVarietyService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tradingvariety")
public class TradingVarietyController {
    @Autowired
    ITradingVarietyService tradingVarietyService;

    @RequestMapping("/findAllByPage")
    public Result findAllByPage(RequestParam requestParam) {
        IPage<TradingVariety> allTradingVariety = tradingVarietyService.findAllByPage(requestParam);
        if (allTradingVariety != null) {
            Result result = Result.success(allTradingVariety.getRecords());
            result.setTotal((int) allTradingVariety.getTotal());
            return result;
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @RequestMapping("/findById")
    public Result<TradingVariety> findById(int id) {
        TradingVariety byId = tradingVarietyService.findById(id);
        return byId != null ? Result.success(byId) : Result.fail(500, "请重新尝试");
    }

    @RequestMapping("/findByKeyWord")
    public Result<List<String>> findByKeyWord(String keyWord) {
        List<String> byKeyWord = tradingVarietyService.findByKeyWord(keyWord);
        return byKeyWord != null ? Result.success(byKeyWord) : Result.fail(500, "请重新尝试");
    }
}
