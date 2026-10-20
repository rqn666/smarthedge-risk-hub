package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.SpotProductsMapper;
import com.ape.smarthedge_risk_hub.pojo.SpotProducts;
import com.ape.smarthedge_risk_hub.service.ISportProductsService;
import com.ape.smarthedge_risk_hub.util.DateHandle;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.List;

@Service
public class SpotProductsServiceImpl extends ServiceImpl<SpotProductsMapper, SpotProducts> implements ISportProductsService {
    @Autowired
    SpotProductsMapper spotProductsMapper;

    @Override
    public int insertSpotProducts(SpotProducts spotProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        DateHandle.dateHandle(spotProducts,"updateTime");
        return spotProductsMapper.insertSpotProducts(spotProducts);
    }

    @Override
    public int updateSpotProducts(SpotProducts spotProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        DateHandle.dateHandle(spotProducts,"updateTime");
        return spotProductsMapper.updateSpotProducts(spotProducts);
    }


    @Override
    public IPage<SpotProducts> findByPage(RequestParam requestParam) {

        IPage<SpotProducts> page = new Page(requestParam.getPage(), requestParam.getSize());
        LambdaQueryWrapper<SpotProducts> lqw = new LambdaQueryWrapper<>();
        if (requestParam.getKeyword() != null && !requestParam.getKeyword().trim().isEmpty()) {
            lqw.like(SpotProducts::getSpotName, requestParam.getKeyword()).or().like(SpotProducts::getSpotSpec, requestParam.getKeyword());
        }

        return spotProductsMapper.selectPage(page, lqw);

    }

    @Override
    public List<SpotProducts> findAll() {
        return spotProductsMapper.findAllSpotProducts();
    }


}
