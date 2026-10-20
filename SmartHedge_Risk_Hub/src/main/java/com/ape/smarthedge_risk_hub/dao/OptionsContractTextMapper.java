package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.OptionsContractText;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

public interface OptionsContractTextMapper extends BaseMapper<OptionsContractText> {
    @Results({
            @Result(id = true,property = "textId",column = "text_id"),
            @Result(property = "varietyId",column = "variety_id"),
            @Result(property = "tradingVariety",column = "variety_id",one = @One(select = "com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper.findById")),
    })
    @Select("select oct.*,e.exchange_name, i.industry_name ,tv.variety_name from options_contract_text oct join options_contract oc on oct.option_id=oc.option_id " +
            "join trading_variety tv on oct.variety_id=tv.variety_id " +
            "join exchange e on oct.exchange_id = e.exchange_id " +
            "join industry i on tv.industry_id=i.industry_id " +
            "where #{keyWord}=''or #{keyWord} is null or underlying_asset like concat('%',#{keyWord},'%') or oct.option_type like concat('%',#{keyWord},'%') or exchange_name like concat('%',#{keyWord},'%') or industry_name like concat('%',#{keyWord},'%')")
    Page<OptionsContractText> findByPage(IPage<OptionsContractText> optionsContractTextIPage,String keyWord);

    @Insert("insert into options_contract_text(option_id, variety_id, exchange_id, underlying_asset, option_type, trade_unit, quote_unit, min_price_move, limit_range, contract_months, trade_time, last_trading_day_rule, expiry_date_rule, strike_price_rule, exercise_method, trade_code, update_time) values (#{optionId},#{varietyId},#{exchangeId},#{underlyingAsset},#{optionType},#{tradeUnit},#{quoteUnit},#{minPriceMove},#{limitRange},#{contractMonths},#{tradeTime},#{lastTradingDayRule},#{expiryDateRule},#{strikePriceRule},#{exerciseMethod},#{tradeCode},now())")
    @Options(useGeneratedKeys = true, keyProperty = "textId", keyColumn = "text_id")
    int insertText(OptionsContractText optionsContractText);
    @Update("update options_contract_text set option_id=#{optionId}, variety_id=#{varietyId}, exchange_id=#{exchangeId}, underlying_asset=#{underlyingAsset}, option_type=#{optionType}, trade_unit=#{tradeUnit}, quote_unit=#{quoteUnit}, min_price_move=#{minPriceMove}, limit_range=#{limitRange}, contract_months=#{contractMonths}, trade_time=#{tradeTime}, last_trading_day_rule=#{lastTradingDayRule}, expiry_date_rule=#{expiryDateRule}, strike_price_rule=#{strikePriceRule}, exercise_method=#{exerciseMethod}, trade_code=#{tradeCode}, update_time=now()" +
            " where text_id=#{textId}")
    int updateText(OptionsContractText optionsContractText);
}
