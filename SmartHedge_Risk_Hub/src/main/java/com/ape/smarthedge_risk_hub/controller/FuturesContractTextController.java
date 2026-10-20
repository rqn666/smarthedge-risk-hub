package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.annotation.OperLog;
import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractText;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractTextAddDTO;
import com.ape.smarthedge_risk_hub.service.Imp.FuturesContractTextServiceImp;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/futureContractText")
public class FuturesContractTextController {
    @Autowired
    FuturesContractTextServiceImp futuresContractTextServiceImp;


    @RequestMapping("/findByPage")
    public Result findByPage(RequestParam requestParam) {
        Page<FuturesContractText> byPage = futuresContractTextServiceImp.findByPage(requestParam);
        Result<List<FuturesContractText>> success = Result.success(byPage.getRecords());
        success.setTotal((int) byPage.getTotal());
        return success;
    }

    @OperLog(module = "期货合约条款", action = "删除")
    @RequestMapping("/deleteText")
    public Result deleteText(Integer textId) {
        boolean b = futuresContractTextServiceImp.removeById(textId);
        if (b = true) {
            return Result.success(null);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }
    @OperLog(module = "期货合约条款", action = "新增")
    @PostMapping("/insertText")
    public Result insertText(@RequestBody FuturesContractTextAddDTO futuresContractTextAddDTO){
        int i = futuresContractTextServiceImp.insertText(futuresContractTextAddDTO);
        return i>0?Result.success(null):Result.fail(500,"请重新尝试");
    }
    @OperLog(module = "期货合约条款", action = "修改")
    @PutMapping("/updateText")
    public Result updateText(@RequestBody FuturesContractTextAddDTO futuresContractTextAddDTO,int textId){
        int i = futuresContractTextServiceImp.updateText(futuresContractTextAddDTO,textId);
        return i>0?Result.success(null):Result.fail(500,"请重新尝试");
    }
}
