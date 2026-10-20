package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractText;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractTextAddDTO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;

public interface IFuturesContractTextService extends IService<FuturesContractText> {
    Page<FuturesContractText> findByPage(RequestParam requestParam);
    int insertText(FuturesContractTextAddDTO futuresContractTextAddDTO);
    int updateText(FuturesContractTextAddDTO futuresContractTextAddDTO,int textId);
}
