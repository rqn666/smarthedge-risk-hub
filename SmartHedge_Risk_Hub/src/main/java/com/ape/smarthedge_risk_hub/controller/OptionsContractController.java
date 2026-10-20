package com.ape.smarthedge_risk_hub.controller;


import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.OptionsContract;
import com.ape.smarthedge_risk_hub.service.IOptionsContractService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/optionsContract")
public class OptionsContractController {
    @Autowired
    IOptionsContractService optionsContractService;

    @RequestMapping("/findByPage")
    public Result findByPage(RequestParam requestParam, String varietyName) {
        Page<OptionsContract> byPage = optionsContractService.findByPage(requestParam, varietyName);
        Result<List<OptionsContract>> success = Result.success(byPage.getRecords());
        success.setTotal((int) byPage.getTotal());
        return success;
    }

    @RequestMapping("/deleteById")
    public Result deleteById(int id) {
        int i = optionsContractService.deleteById(id);
        return i > 0 ? Result.success(null) : Result.fail(500, "请重新尝试");
    }

    @RequestMapping("/insertContract")
    public Result insertContract(String optionCode, String optionName, String updateMethod, Integer varietyId, Date updateTime, @org.springframework.web.bind.annotation.RequestParam(name = "underlyingContractId", defaultValue = "4") int underlyingContractId, @org.springframework.web.bind.annotation.RequestParam(name = "strikePrice", defaultValue = "7000") int strikePrice, @DateTimeFormat(pattern = "yyyy-MM-dd") @org.springframework.web.bind.annotation.RequestParam(name = "lastTradingDay", defaultValue = "2020-05-05") Date lastTradingDay) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = optionsContractService.insertContract(optionCode, optionName, updateMethod, varietyId, updateTime, underlyingContractId, strikePrice, lastTradingDay);
        return i > 0 ? Result.success(null) : Result.fail(500, "请重新尝试");
    }

    @RequestMapping("/updateContract")
    public Result updateContract(@RequestBody OptionsContract optionsContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = optionsContractService.updateContract(optionsContract);
        return i > 0 ? Result.success(null) : Result.fail(500, "请重新尝试");
    }
}
