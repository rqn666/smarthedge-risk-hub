package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.SpotProducts;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;


public interface SpotProductsMapper extends BaseMapper<SpotProducts> {

    @Insert("insert into spot_products (spot_name, spot_spec, origin_id, spot_unit,update_time) values (#{spotName},#{spotSpec},#{originId},#{spotUnit},NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    public int insertSpotProducts(SpotProducts sportProducts);

    @Update("update spot_products set spot_name=#{spotName},spot_spec=#{spotSpec},origin_id=#{originId},spot_unit=#{spotUnit},update_time=NOW() where id=#{id}")
    public int updateSpotProducts(SpotProducts spotProducts);

//    @Results({
//            @Result(id = true, property = "id", column = "id"),
//            @Result(property = "spotName", column = "spot_name"),
//            @Result(property = "spotSpec", column = "spot_spec"),
//            @Result(property = "originId", column = "origin_id"),
//            @Result(property = "spotUnit", column = "spot_unit"),
//            @Result(property = "updateTime", column = "update_time"),
//            @Result(column = "id", property = "originDict", one = @One(select = "com.ape.smarthedge_risk_hub.dao.OriginDictMapper.findById")),
//    })
//    @Select("select * from spot_products where id=#{id}")
//    public SpotProducts findByIdAndOriginDict(int id);

    @Select("select * from spot_products where origin_id=#{originId}")
    List<SpotProducts> findByOriginId(@Param("originId") int originId);

    @Results({
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "spotName", column = "spot_name"),
            @Result(property = "spotSpec", column = "spot_spec"),
            @Result(property = "originId", column = "origin_id"),
            @Result(property = "spotUnit", column = "spot_unit"),
            @Result(property = "originDict.originName", column = "origin_name"),
            @Result(property = "originDict.originShortName", column = "origin_short_name"),
            @Result(property = "originDict.createTime", column = "create_time")
    })
    @Select("select s.spot_name,o.origin_name,s.spot_spec,s.spot_unit,s.update_time  from spot_products s,origin_dict o where s.origin_id=o.origin_id ")
    List<SpotProducts> findAllSpotProducts();
}
