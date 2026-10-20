package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.OptionsContract;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.Date;

public interface IOptionsContractService {
    Page<OptionsContract> findByPage(RequestParam requestParam, String varietyName);

    int insertContract(String optionCode, String optionName, String updateMethod, int varietyId, Date updateTime, int underlyingContractId, int strikePrice, Date lastTradingDay) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;

    int updateContract(OptionsContract optionsContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;

    /** 按主键删除，同时清理 ES 文档 */
    int deleteById(int id);
}
