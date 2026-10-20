package com.ape.smarthedge_risk_hub.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class RequestParam {
    private String keyword;
    /**
     * 页数 从1开始
     */
    private Long page;
    /**
     * 每页显示条数
     */
    private Long size;



}
