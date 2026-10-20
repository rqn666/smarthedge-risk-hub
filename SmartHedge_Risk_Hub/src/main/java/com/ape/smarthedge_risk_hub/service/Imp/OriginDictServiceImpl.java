package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.dao.OriginDictMapper;
import com.ape.smarthedge_risk_hub.pojo.OriginDict;
import com.ape.smarthedge_risk_hub.service.IOriginDictService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;



@Service
public class OriginDictServiceImpl extends ServiceImpl<OriginDictMapper, OriginDict> implements IOriginDictService {
    @Autowired
    OriginDictMapper originDictMapper;

    @Override
    public OriginDict findByIdAndSpotProducts(int originId) {
        return originDictMapper.findByIdAndSpotProducts(originId);
    }
}
