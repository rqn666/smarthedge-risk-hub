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
public class SupplyCategory {
    private int categoryId;
    private String categoryName;
    private String specifications;
    private String origin;
    private String unit;
    private Date createTime;
    @TableField(exist = false)
    private List<Supplier> supplierList;


}
