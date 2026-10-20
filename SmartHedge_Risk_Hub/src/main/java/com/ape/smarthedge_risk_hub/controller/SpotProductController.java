package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.SpotProducts;
import com.ape.smarthedge_risk_hub.service.ISportProductsService;
import com.baomidou.mybatisplus.core.metadata.IPage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.List;

@RestController
@RequestMapping("/api/spotproduct")
public class SpotProductController {
    @Autowired
    ISportProductsService sportProductsService;


    @RequestMapping("/save")
    public Result save(@RequestBody SpotProducts spotProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = sportProductsService.insertSpotProducts(spotProducts);
        if (i != 0) {
            return Result.success(spotProducts);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @GetMapping("/findAll")
    public Result<List<SpotProducts>> findAll() {
        List<SpotProducts> list = sportProductsService.list();
        if (list != null) {
            return Result.success(list);
        } else {
            return Result.fail(500, "请重新尝试");
        }

    }

    @PutMapping("/update")
    public Result update(@RequestBody SpotProducts spotProducts) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = sportProductsService.updateSpotProducts(spotProducts);
        if (i != 0) {
            return Result.success(spotProducts);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @GetMapping("/findByPage")
    public Result findByPage(RequestParam requestParam) {
        IPage<SpotProducts> pageResult = sportProductsService.findByPage(requestParam);
        if (pageResult != null) {
            Result result= Result.success(pageResult.getRecords());
            result.setTotal((int)pageResult.getTotal());
            return result;
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @GetMapping("/findAllSpotProducts")
    public Result findAllSpotProducts() {
        List<SpotProducts> allSpotProducts = sportProductsService.findAll();
        if (allSpotProducts != null) {
            return Result.success(allSpotProducts);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }
}
