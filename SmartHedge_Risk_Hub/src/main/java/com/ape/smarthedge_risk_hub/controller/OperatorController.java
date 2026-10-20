package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.Operator;
import com.ape.smarthedge_risk_hub.service.IOperatorService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {
    @Autowired
    IOperatorService operatorService;
    @RequestMapping("findAll")
    public Result<List<Operator>> findAllOperatorById(){
        List<Operator> operatorList=operatorService.list();
        if(operatorList!=null){
            return Result.success(operatorList);
        }else{
            return Result.fail(500,"请重新尝试");
        }
    }
}
