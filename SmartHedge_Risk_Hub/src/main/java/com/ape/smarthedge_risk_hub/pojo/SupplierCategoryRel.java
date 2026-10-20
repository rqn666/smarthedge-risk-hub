package com.ape.smarthedge_risk_hub.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SupplierCategoryRel {
    private int relId;
    private int supplierId;
    private int categoryId;
}
