package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.Supplier;
import com.ape.smarthedge_risk_hub.pojo.SupplyCategory;
import com.ape.smarthedge_risk_hub.service.ISupplyCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/supplycategory")
public class SupplyCategoryController {
    @Autowired
    ISupplyCategoryService supplyCategoryService;
    @RequestMapping("/findAllSupplyCategory")
    public Result<List<SupplyCategory>> findAllSupplyCategory() {
        List<SupplyCategory> list = supplyCategoryService.list();
        if (list != null) {
            return Result.success(list);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }
}
