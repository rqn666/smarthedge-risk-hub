package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.FuturesContractMapper;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.sync.EsSyncService;
import com.ape.smarthedge_risk_hub.es.sync.EsWriteHelper;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.service.IFuturesContractService;
import com.ape.smarthedge_risk_hub.util.DateHandle;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.text.SimpleDateFormat;


@Service
public class FuturesContractServiceImpl implements IFuturesContractService {
    @Autowired
    FuturesContractMapper futuresContractMapper;

    /** ES 双写：MySQL 写成功后同步到 ES，失败落重试表 */
    @Autowired
    EsWriteHelper esWriteHelper;

    @Override
    public IPage<FuturesContract> findAllByPage(RequestParam requestParam, String varietyName) {
        IPage<FuturesContract> futuresContractIPage = new Page<>(requestParam.getPage(), requestParam.getSize());
        IPage<FuturesContract> allByPage = futuresContractMapper.findAllByPage(futuresContractIPage, varietyName, requestParam.getKeyword());
        return allByPage;
    }

    @Override
    public int deleteById(int id) {
        int rows = futuresContractMapper.deleteById(id);
        if (rows > 0) {
            esWriteHelper.deleteAfterCommit(EsIndex.FUTURES_CONTRACT, id);
        }
        return rows;
    }

    @Override
    public int insertContract(FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        DateHandle.dateHandle(futuresContract, "updateTime");
        DateHandle.dateHandle(futuresContract, "createTime");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
        simpleDateFormat.format(futuresContract.getLastTradingDay());
        int rows = futuresContractMapper.insertContract(futuresContract);
        if (rows > 0) {
            // insertContract 的 XML 配了 useGeneratedKeys，主键已回填到对象
            esWriteHelper.upsertAfterCommit(EsIndex.FUTURES_CONTRACT, futuresContract);
        }
        return rows;
    }

    @Override
    public int updateContract(FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        DateHandle.dateHandle(futuresContract, "updateTime");
        DateHandle.dateHandle(futuresContract, "createTime");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
        simpleDateFormat.format(futuresContract.getLastTradingDay());
        int rows = futuresContractMapper.updateContract(futuresContract);
        if (rows > 0) {
            esWriteHelper.upsertAfterCommit(EsIndex.FUTURES_CONTRACT, futuresContract);
        }
        return rows;
    }
}
