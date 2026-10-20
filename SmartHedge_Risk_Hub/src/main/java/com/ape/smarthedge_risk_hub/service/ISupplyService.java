package com.ape.smarthedge_risk_hub.service;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.Supplier;
import com.ape.smarthedge_risk_hub.pojo.SupplierAddDTO;
import com.baomidou.mybatisplus.core.metadata.IPage;

public interface ISupplyService {
    IPage<Supplier> findAllSupplyByPage(RequestParam requestParam);

    void addSupplierwithCategory(SupplierAddDTO supplierAddDTO);
    void updateSupplierwithCategory(SupplierAddDTO supplierAddDTO);
    void delete(int id);
}
