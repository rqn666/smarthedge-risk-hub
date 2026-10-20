package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.List;

public interface IFuturesContractService {
    IPage<FuturesContract> findAllByPage(RequestParam requestParam, String varietyName);

    int deleteById(int id);

    int insertContract(FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;

    int updateContract(FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;
}
