package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Supplier {
    private int supplierId;
    private String supplierName;
    private String contactPhone;
    private String address;
    private String creditCode;
    private int operatorId;
    private int score;
    private int status;
    private Date updateTime;
    private Date createTime;
    @TableField(exist = false)
    private Operator operator;
    private List<SupplyCategory> supplyCategoryList;
}
