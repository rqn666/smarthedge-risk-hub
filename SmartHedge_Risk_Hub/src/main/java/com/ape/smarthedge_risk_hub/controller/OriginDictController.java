package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.OriginDict;
import com.ape.smarthedge_risk_hub.service.IOriginDictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/origindict")
public class OriginDictController {
    @Autowired
    IOriginDictService originDictService;

    @GetMapping("/findAll")
    public Result<List<OriginDict>> findAll() {
        List<OriginDict> all = originDictService.list();
        if (all != null) {
            return Result.success(all);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @GetMapping("/findByIdAndSpotProducts")
    public Result<OriginDict> findByIdAndSpotProducts(int originId) {
        OriginDict byIdAndSpotProducts = originDictService.findByIdAndSpotProducts(originId);
        if (byIdAndSpotProducts != null) {
            return Result.success(byIdAndSpotProducts);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }
}
