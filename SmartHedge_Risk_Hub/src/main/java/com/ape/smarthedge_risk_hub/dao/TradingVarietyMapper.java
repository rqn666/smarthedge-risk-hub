package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.util.List;


public interface TradingVarietyMapper extends BaseMapper<TradingVariety> {
    @Results({
            @Result(id = true, property = "varietyId", column = "variety_id"),
            @Result(property = "varietyName", column = "variety_name"),
            @Result(property = "varietyCode", column = "variety_code"),
            @Result(property = "exchangeId", column = "exchange_id"),
            @Result(property = "industryId", column = "industry_id"),
            @Result(property = "industry.industryName", column = "industry_name"),
            @Result(property = "exchange.exchangeName", column = "exchange_name"),
            @Result(property = "exchange.exchangeName", column = "exchange_name")

    })
    @Select("select tv.variety_name,tv.variety_code,e.exchange_name,i.industry_name from trading_variety tv " +
            "left join industry i on tv.industry_id = i.industry_id " +
            "left join exchange e on tv.exchange_id = e.exchange_id where tv.variety_name like concat('%',#{keyword},'%') " +
            "or tv.variety_code like concat('%',#{keyword},'%') " +
            "or i.industry_name like concat('%',#{keyword},'%') " +
            "or e.exchange_name like concat('%',#{keyword},'%') ")
    Page<TradingVariety> findAllByPage(IPage<TradingVariety> tradingVarietyIPage, @Param("keyword") String keyword);

    @Results({
            @Result(id = true, property = "varietyId", column = "variety_id"),
            @Result(property = "varietyName", column = "variety_name"),
            @Result(property = "varietyCode", column = "variety_code"),
            @Result(property = "exchangeId", column = "exchange_id"),
            @Result(property = "industryId", column = "industry_id"),
            @Result(column = "exchange_id", property = "exchange", one = @One(select = "com.ape.smarthedge_risk_hub.dao.ExchangeMapper.findById")),
            @Result(column = "industry_id", property = "industry", one = @One(select = "com.ape.smarthedge_risk_hub.dao.IndustryMapper.findById"))
    })
    @Select("select * from trading_variety where variety_id=#{id}")
    TradingVariety findById(int id);
    @Select("select variety_name from trading_variety where variety_name like concat('%',#{keyWord},'%')")
    List<String> findByKeyWord(String keyWord);
}
