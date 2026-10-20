package com.ape.smarthedge_risk_hub.service;


import com.ape.smarthedge_risk_hub.pojo.OriginDict;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IOriginDictService extends IService<OriginDict> {
    OriginDict findByIdAndSpotProducts(int originId);

}
