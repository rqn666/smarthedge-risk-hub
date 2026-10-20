package com.ape.smarthedge_risk_hub.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SupplierAddDTO {
    private int id;
    private int status;
    @NotBlank(message = "供应商名称不能为空")
    private String supplierName;

    @NotBlank(message = "联系电话不能为空")
    private String contactPhone;

    @NotBlank(message = "地址不能为空")
    private String address;

    @NotBlank(message = "统一信用代码不能为空")
    private String creditCode;

    @NotBlank(message = "经营者不能为空")
    private String operatorName;

    @NotNull(message = "评分不能为空")
    private Integer score;

    @NotEmpty(message = "供货品类不能为空")
    private List<String> categoryNames;
}
