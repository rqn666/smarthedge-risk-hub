package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.Industry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface IndustryMapper extends BaseMapper<Industry> {
    @Select("select * from industry where industry_id=#{id}")
    Industry findById(int id);
}
