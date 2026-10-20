package com.ape.smarthedge_risk_hub.controller;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.pojo.OptionsContractText;
import com.ape.smarthedge_risk_hub.pojo.OptionsContractTextAddDTO;
import com.ape.smarthedge_risk_hub.service.Imp.OptionContractTextServiceImp;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/optionContractText")
public class OptionContractTextController {
    @Autowired
    OptionContractTextServiceImp optionContractTextServiceImp;

    @GetMapping("/findByPage")
    public Result findByPage(RequestParam requestParam) {
        Page<OptionsContractText> byPage = optionContractTextServiceImp.findByPage(requestParam);
        Result<List<OptionsContractText>> success = Result.success(byPage.getRecords());
        success.setTotal((int) byPage.getTotal());
        return Result.success(success);
    }

    @DeleteMapping("/deleteById")
    public Result deleteById(int textId) {
        boolean b = optionContractTextServiceImp.removeById(textId);
        if (b = true) {
            return Result.success(null);
        } else {
            return Result.fail(500, "请重新尝试");
        }
    }

    @PostMapping("insertText")
    public Result insertText(@RequestBody OptionsContractTextAddDTO optionsContractTextAddDTO) {
        return optionContractTextServiceImp.insertText(optionsContractTextAddDTO) > 0 ? Result.success(null) : Result.fail(500, "请重新尝试");
    }

    @PutMapping("/updateText")
    public Result updateText(@RequestBody OptionsContractTextAddDTO optionsContractTextAddDTO, int id) {
        return optionContractTextServiceImp.updateText(optionsContractTextAddDTO, id) > 0 ? Result.success(null) : Result.fail(500, "请重新尝试");
    }
}
