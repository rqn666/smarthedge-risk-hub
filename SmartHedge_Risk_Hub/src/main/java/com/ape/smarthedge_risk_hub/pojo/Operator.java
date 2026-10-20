package com.ape.smarthedge_risk_hub.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Operator {
    private int operatorId;
    private String operatorName;
    private int status;
    private Date createTime;
    private Date updateTime;
}
