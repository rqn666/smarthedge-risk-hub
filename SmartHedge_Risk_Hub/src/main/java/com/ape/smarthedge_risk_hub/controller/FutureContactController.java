package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.annotation.OperLog;
import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.service.IFuturesContractService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;

@RestController
@RequestMapping("/api/futurecontract")
public class FutureContactController {
    @Autowired
    IFuturesContractService futuresContractService;

    @RequestMapping("/deleteById")
    public Result deleteById(int id) {
        int i = futuresContractService.deleteById(id);
        if (i != 0) {
            return Result.success(null);
        } else {
            return Result.fail(500, "删除失败");
        }
    }

    @RequestMapping("/findAllByPage")
    public Result findAllByPage(RequestParam requestParam, String varietyName) {
        IPage<FuturesContract> allByPage = futuresContractService.findAllByPage(requestParam, varietyName);
        long total = allByPage.getTotal();
        Result success = Result.success(allByPage.getRecords());
        success.setTotal((int) total);
        return success;
    }

    @OperLog(module = "期货合约", action = "新增")
    @PostMapping("/insertContract")
    public Result insertContract(@RequestBody FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = futuresContractService.insertContract(futuresContract);
        if (i > 0) {
            return Result.success(null);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @OperLog(module = "期货合约", action = "修改")
    @PutMapping("/updateContract")
    public Result updateContract(@RequestBody FuturesContract futuresContract) throws ParseException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        int i = futuresContractService.updateContract(futuresContract);
        if (i > 0) {
            return Result.success(null);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }
}
