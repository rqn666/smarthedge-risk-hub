package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.Exchange;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface ExchangeMapper extends BaseMapper<Exchange> {
    @Select("select * from exchange where exchange_id=#{id}")
    Exchange findById(int id);
}
