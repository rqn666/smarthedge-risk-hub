package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper;
import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import com.ape.smarthedge_risk_hub.service.ITradingVarietyService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class TradingVarietyServiceImpl implements ITradingVarietyService {
    @Autowired
    TradingVarietyMapper tradingVarietyMapper;

    @Override
    public IPage<TradingVariety> findAllByPage(RequestParam requestParam) {
        Page<TradingVariety> tradingVarietyPage = new Page<>(requestParam.getPage(), requestParam.getSize());
        return tradingVarietyMapper.findAllByPage(tradingVarietyPage, requestParam.getKeyword());
    }

    @Override
    public TradingVariety findById(int id) {
        return tradingVarietyMapper.selectById(id);
    }

    @Override
    public List<String> findByKeyWord(String keyWord) {
        return tradingVarietyMapper.findByKeyWord(keyWord);
    }
}
