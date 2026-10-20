package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.*;

public interface FuturesContractMapper extends BaseMapper<FuturesContract> {
    @Results({
            @Result(id = true, property = "contractId", column = "contract_id"),
            @Result(property = "contractCode", column = "contract_code"),
            @Result(property = "contractName", column = "contract_name"),
            @Result(property = "lastTradingDay", column = "last_trading_day"),
            @Result(property = "varietyId", column = "variety_id"),
            @Result(property = "updateTime", column = "update_time"),
            @Result(property = "createTime", column = "create_time"),
            @Result(column = "variety_id", property = "tradingVariety", one = @One(select = "com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper.findById"))
    })
    @Select("select fc.*,tv.* from futures_contract fc " +
            "join trading_variety tv on fc.variety_id=tv.variety_id " +
            "where (#{varietyName} is null or #{varietyName}='' or tv.variety_name like concat('%',#{varietyName},'%')) " +
            "and (#{keyWord} is null or #{keyWord}='' or fc.contract_code like concat('%',#{keyWord},'%') or fc.contract_name like concat('%',#{keyWord},'%')) ")
    IPage<FuturesContract> findAllByPage(IPage<FuturesContract> futuresContractIPage,
                                         @Param("varietyName") String varietyName,
                                         @Param("keyWord")String keyWord);

    @Insert("insert into futures_contract(contract_code,contract_name,last_trading_day,variety_id,update_method,update_time,create_time) values (#{contractCode},#{contractName},#{lastTradingDay},#{varietyId},#{updateMethod},now(),now())")
    @Options(useGeneratedKeys = true, keyProperty = "contractId", keyColumn = "contract_id")
    int insertContract(FuturesContract futuresContract);
    @Update("update futures_contract set contract_code=#{contractCode},contract_name=#{contractName},last_trading_day=#{lastTradingDay},variety_id=#{varietyId},update_method=#{updateMethod},update_time=now(),create_time=now() where contract_id=#{contractId}")
    int updateContract(FuturesContract futuresContract);
    @Select("select * from futures_contract where contract_id=#{contractId}")
    FuturesContract findById(int contractId);
}


