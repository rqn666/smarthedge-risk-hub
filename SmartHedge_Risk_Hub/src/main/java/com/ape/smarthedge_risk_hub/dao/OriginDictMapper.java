package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.OriginDict;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.mapping.FetchType;



public interface OriginDictMapper extends BaseMapper<OriginDict> {
    @Results({
            @Result(id = true,property = "originId",column = "origin_id"),
            @Result(id = false,property = "originName",column = "origin_name"),
            @Result(id = false,property = "originShortName",column = "origin_short_name"),
            @Result(id = false,property = "createTime",column = "create_time"),
            @Result(id = false,property = "spotProductsList",column = "origin_id",many = @Many(select = "com.ape.smarthedge_risk_hub.dao.SpotProductsMapper.findByOriginId")),
    })
    @Select("select * from origin_dict where origin_id=#{originId}")
    OriginDict findByIdAndSpotProducts(int originId);
}
