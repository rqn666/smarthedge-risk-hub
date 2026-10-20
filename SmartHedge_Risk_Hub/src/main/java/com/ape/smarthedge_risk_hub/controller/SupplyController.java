package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.annotation.OperLog;
import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.Supplier;
import com.ape.smarthedge_risk_hub.pojo.SupplierAddDTO;
import com.ape.smarthedge_risk_hub.service.ISupplyService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("api/supply")
public class SupplyController {
    @Autowired
    ISupplyService supplyService;

    @RequestMapping("/findAllByPage")
    public Result findAllByPage(RequestParam requestParam) {
        IPage<Supplier> allSupplyBuPage = supplyService.findAllSupplyByPage(requestParam);
        if (allSupplyBuPage != null) {
            Result success = Result.success(allSupplyBuPage.getRecords());
            success.setTotal((int) allSupplyBuPage.getTotal());
            return success;
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @OperLog(module = "供应商", action = "新增")
    @PostMapping("/saveSupply")
    public Result saveSupply(@Valid @RequestBody SupplierAddDTO supplierAddDTO) {
        supplyService.addSupplierwithCategory(supplierAddDTO);
        return Result.success(null);
    }

    @OperLog(module = "供应商", action = "修改")
    @PutMapping("/updateSupply")
    public Result updateSupply(@Valid @RequestBody SupplierAddDTO supplierAddDTO) {
        supplyService.updateSupplierwithCategory(supplierAddDTO);
        return Result.success(null);
    }
    @OperLog(module = "供应商", action = "删除")
    @DeleteMapping("/deleteSupply")
    public Result deleteById(int id){
        supplyService.delete(id);
        return Result.success(null);
    }
}
