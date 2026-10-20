package com.ape.smarthedge_risk_hub.dao;

import com.ape.smarthedge_risk_hub.pojo.Supplier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;


public interface SupplierMapper extends BaseMapper<Supplier> {
    @Results({
            @Result(id = true, property = "supplierId", column = "supplier_id"),
            @Result(property = "supplierName", column = "supplier_name"),
            @Result(property = "contactPhone", column = "contact_phone"),
            @Result(property = "address", column = "address"),
            @Result(property = "creditCode", column = "credit_code"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "score", column = "score"),
            @Result(property = "status", column = "status"),
            @Result(property = "score", column = "score"),
            @Result(property = "updateTime", column = "update_time"),
            @Result(property = "createTime", column = "create_time"),
            @Result(property = "operator.operatorName", column = "operator_name")
    })

    @Select("select distinct s.*,o.* from supplier s " +
            "left join  operator o on s.operator_id=o.operator_id " +
            "left join supplier_category_rel src on s.supplier_id=src.supplier_id " +
            "left join supply_category sc  on sc.category_id=src.category_id " +
            "where s.supplier_name like concat('%',#{keyword},'%') " +
            "or sc.category_name like concat('%',#{keyword},'%') " +
            "or s.credit_code like concat('%',#{keyword},'%')")
    Page<Supplier> findAllSupplyByPage(IPage<Supplier> SupplyPage, String keyword);

    @Select("select * from supplier where supplier_id=#{id}")
    Supplier findById(int id);

    @Insert("insert into supplier(supplier_name, contact_phone, address, credit_code, operator_id, score, update_time, create_time) " +
            "VALUES (#{supplierName},#{contactPhone},#{address},#{creditCode},#{operatorId},#{score},NOW(),NOW())")
    @Options(useGeneratedKeys = true,keyProperty = "supplierId",keyColumn = "supplier_id")
    int insertSupply(Supplier supplier);

    @Update("update supplier set supplier_name=#{supplierName},contact_phone=#{contactPhone},address=#{address},credit_code=#{creditCode},operator_id=#{operatorId},score=#{score},status=#{status},update_time=now() where supplier_id=#{supplierId}")
    int updateSupplier(Supplier supplier);
    @Delete("DELETE FROM supplier WHERE supplier_id = #{id}")
    int deleteById(int id);

}
