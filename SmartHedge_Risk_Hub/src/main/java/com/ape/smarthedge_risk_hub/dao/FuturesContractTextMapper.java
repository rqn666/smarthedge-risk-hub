package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.FuturesContractText;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface FuturesContractTextMapper extends BaseMapper<FuturesContractText> {
    @Results({
            @Result(id = true, property = "textId", column = "text_id"),
            @Result(property = "contractId", column = "contract_id"),
            @Result(property = "varietyId", column = "variety_id"),
            @Result(property = "exchangeId", column = "exchange_id"),
            @Result(property = "futuresContract", column = "contract_id",one = @One(select="com.ape.smarthedge_risk_hub.dao.FuturesContractMapper.findById")),
            @Result(property = "tradingVariety", column = "variety_id",one=@One(select = "com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper.findById")),
            @Result(property = "exchange", column = "exchange_id",one=@One(select = "com.ape.smarthedge_risk_hub.dao.ExchangeMapper.findById")),
    })
    @Select("select fct.*,fc.contract_code,tv.variety_name,e.exchange_name,i.industry_name from futures_contract_text fct " +
            "join futures_contract fc on fct.contract_id = fc.contract_id " +
            "join trading_variety tv on fct.variety_id = tv.variety_id " +
            "join exchange e on tv.exchange_id = e.exchange_id " +
            "join industry i on i.industry_id=tv.industry_id " +
            "where #{keyWord}='' or #{keyWord} is null or tv.variety_name like concat('%',#{keyWord},'%')" +
            "or fc.contract_code like concat('%',#{keyWord},'%')" +
            "or e.exchange_name like concat('%',#{keyWord},'%')" +
            "or i.industry_name like concat('%',#{keyWord},'%')")
    Page<FuturesContractText> findByPage(Page<FuturesContractText> fPage, String keyWord);
    @Insert("insert into futures_contract_text(contract_id, variety_id, exchange_id, trade_unit, quote_unit, min_price_move, limit_range, contract_months, trade_time, last_trading_day_rule, delivery_date_rule, min_margin,update_time) values (#{contractId},#{varietyId},#{exchangeId},#{tradeUnit},#{quoteUnit},#{minPriceMove},#{limitRange},#{contractMonths},#{tradeTime},#{lastTradingDayRule},#{deliveryDateRule},#{minMargin},now())")
    @Options(useGeneratedKeys = true, keyProperty = "textId", keyColumn = "text_id")
    int insertText(FuturesContractText futuresContractText);
    @Update("update futures_contract_text set contract_id=#{contractId}, variety_id=#{varietyId}, exchange_id=#{exchangeId}, trade_unit=#{tradeUnit}, quote_unit=#{quoteUnit}, min_price_move=#{minPriceMove}, limit_range=#{limitRange}, contract_months=#{contractMonths}, trade_time=#{tradeTime}, last_trading_day_rule=#{lastTradingDayRule}, delivery_date_rule=#{deliveryDateRule}, min_margin=#{minMargin},update_time=NOW() where text_id=#{textId}")
    int updateText(FuturesContractText futuresContractText);


}
