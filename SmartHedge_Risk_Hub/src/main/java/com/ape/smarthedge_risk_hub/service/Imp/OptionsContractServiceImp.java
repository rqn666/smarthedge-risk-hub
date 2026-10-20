package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.OptionsContractMapper;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.sync.EsWriteHelper;
import com.ape.smarthedge_risk_hub.pojo.OptionsContract;
import com.ape.smarthedge_risk_hub.service.IOptionsContractService;
import com.ape.smarthedge_risk_hub.util.DateHandle;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;


@Service
public class OptionsContractServiceImp implements IOptionsContractService {
    @Autowired
    OptionsContractMapper optionsContractMapper;

    /** ES 双写 */
    @Autowired
    EsWriteHelper esWriteHelper;

    @Override
    public Page<OptionsContract> findByPage(RequestParam requestParam, String varietyName) {
        IPage<OptionsContract> optionsContractIPage1 = new Page<>(requestParam.getPage(), requestParam.getSize());
        return optionsContractMapper.findByPage(optionsContractIPage1, varietyName, requestParam.getKeyword());
    }

    @Override
    public int insertContract(String optionCode, String optionName, String updateMethod, int varietyId, Date updateTime, int underlyingContractId, int strikePrice, Date lastTradingDay) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        OptionsContract optionsContract = new OptionsContract();
        DateHandle.dateHandle(optionsContract, "updateTime");
        optionsContract.setOptionCode(optionCode);
        optionsContract.setOptionName(optionName);
        optionsContract.setUpdateMethod(updateMethod);
        optionsContract.setVarietyId(varietyId);
        optionsContract.setUnderlyingContractId(underlyingContractId);
        optionsContract.setStrikePrice(strikePrice);
        optionsContract.setLastTradingDay(lastTradingDay);
        int rows = optionsContractMapper.insertContract(optionsContract);
        if (rows > 0) {
            esWriteHelper.upsertAfterCommit(EsIndex.OPTIONS_CONTRACT, optionsContract);
        }
        return rows;
    }

    @Override
    public int updateContract(OptionsContract optionsContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        DateHandle.dateHandle(optionsContract, "updateTime");
        SimpleDateFormat simpleDateFormat=new SimpleDateFormat("yyyy-MM-dd");
        simpleDateFormat.format(optionsContract.getLastTradingDay());
        int rows = optionsContractMapper.updateContract(optionsContract);
        if (rows > 0) {
            esWriteHelper.upsertAfterCommit(EsIndex.OPTIONS_CONTRACT, optionsContract);
        }
        return rows;
    }

    /** 按主键删除，同步清 ES 文档 */
    @Override
    public int deleteById(int id) {
        int rows = optionsContractMapper.deleteById(id);
        if (rows > 0) {
            esWriteHelper.deleteAfterCommit(EsIndex.OPTIONS_CONTRACT, id);
        }
        return rows;
    }
}
