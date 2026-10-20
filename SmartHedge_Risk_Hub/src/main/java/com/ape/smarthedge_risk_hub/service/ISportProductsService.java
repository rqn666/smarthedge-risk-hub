package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.SpotProducts;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.List;


public interface ISportProductsService extends IService<SpotProducts> {
    int insertSpotProducts(SpotProducts sportProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;

    int updateSpotProducts(SpotProducts spotProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException;

    IPage<SpotProducts> findByPage(RequestParam requestParam);

    List<SpotProducts> findAll();

}
