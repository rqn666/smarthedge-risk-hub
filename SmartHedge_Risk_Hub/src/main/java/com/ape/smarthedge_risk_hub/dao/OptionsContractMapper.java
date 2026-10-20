package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.OptionsContract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

public interface OptionsContractMapper extends BaseMapper<OptionsContract> {
    @Results({
            @Result(id = true, property = "optionId", column = "option_id"),
            @Result(property = "optionCode", column = "option_code"),
            @Result(property = "optionName", column = "option_name"),
            @Result(property = "lastTradingDay", column = "last_trading_day"),
            @Result(property = "varietyId", column = "variety_id"),
            @Result(property = "updateMethod", column = "update_method"),
            @Result(property = "updateTime", column = "update_time"),
            @Result(property = "createTime", column = "create_time"),
            @Result(column = "variety_id", property = "tradingVariety", one = @One(select = "com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper.findById"))
    })
    @Select("select tv.variety_name, oc.* from options_contract oc join trading_variety tv " +
            "on oc.variety_id = tv.variety_id where tv.variety_name=#{varietyName} and (#{keyWord} is null or #{keyWord} = '' or option_name like  concat('%',#{keyWord},'%') or option_code like  concat('%',#{keyWord},'%'))")
    Page<OptionsContract> findByPage(IPage<OptionsContract> optionsContractIPage,
                                     @Param("varietyName") String varietyName,
                                     @Param("keyWord") String keyWord);

    @Insert("insert into options_contract(option_code,option_name,update_method,variety_id,update_time,underlying_contract_id,strike_price,last_trading_day) values (#{optionCode},#{optionName},#{updateMethod},#{varietyId},now(),#{underlyingContractId},#{strikePrice},#{lastTradingDay})")
    @Options(useGeneratedKeys = true, keyProperty = "optionId", keyColumn = "option_id")
    int insertContract(OptionsContract optionsContract);

    @Update("update options_contract set option_code=#{optionCode},option_name=#{optionName},update_method=#{updateMethod},variety_id=#{varietyId},last_trading_day=#{lastTradingDay} ,update_time=#{updateTime} where option_id=#{optionId}")
    int updateContract(OptionsContract optionsContract);
}
