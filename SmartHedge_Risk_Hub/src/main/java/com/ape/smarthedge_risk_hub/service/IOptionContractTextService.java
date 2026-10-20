package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.OptionsContractText;
import com.ape.smarthedge_risk_hub.pojo.OptionsContractTextAddDTO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IOptionContractTextService extends IService<OptionsContractText> {
    Page<OptionsContractText> findByPage(RequestParam requestParam);
    int insertText(OptionsContractTextAddDTO optionsContractTextAddDTO);
    int updateText(OptionsContractTextAddDTO optionsContractTextAddDTO,int id);
}
