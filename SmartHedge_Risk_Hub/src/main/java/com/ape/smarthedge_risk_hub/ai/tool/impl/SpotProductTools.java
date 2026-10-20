package com.ape.smarthedge_risk_hub.ai.tool.impl;

import com.ape.smarthedge_risk_hub.ai.tool.Tool;
import com.ape.smarthedge_risk_hub.ai.tool.ToolParam;
import com.ape.smarthedge_risk_hub.pojo.OriginDict;
import com.ape.smarthedge_risk_hub.pojo.SpotProducts;
import com.ape.smarthedge_risk_hub.service.ISportProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现货产品工具。复用 findAll() 后在内存里按名称过滤，
 * 因为现货表数据量小（字典类数据），不值得为它加 SQL。
 */
@Component
public class SpotProductTools {

    private static final int MAX_ROWS = 10;

    @Autowired
    private ISportProductsService spotProductsService;

    @Tool(name = "querySpotProduct",
            description = "查询现货产品信息，包括现货名称、规格、产地、计量单位。"
                    + "当用户询问有哪些现货品种、某现货的规格或产地、现货的计量单位时调用。")
    public Map<String, Object> querySpotProduct(
            @ToolParam(value = "现货名称关键字，如 螺纹钢、大豆、铜；不传则返回全部前若干条", required = false) String spotName) {
        List<SpotProducts> all = spotProductsService.findAll();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String keyword = spotName == null ? "" : spotName.trim();
        if (all != null) {
            for (SpotProducts spot : all) {
                if (!keyword.isEmpty() && !containsIgnoreCase(spot.getSpotName(), keyword)
                        && !containsIgnoreCase(spot.getSpotSpec(), keyword)) {
                    continue;
                }
                rows.add(toRow(spot));
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("spotName", spotName);
        result.put("totalScanned", all == null ? 0 : all.size());
        result.put("count", rows.size());
        result.put("records", rows);
        if (rows.isEmpty()) {
            result.put("message", "未查询到与「" + keyword + "」匹配的现货产品");
        }
        return result;
    }

    private Map<String, Object> toRow(SpotProducts spot) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("spotName", spot.getSpotName());
        row.put("spotSpec", spot.getSpotSpec());
        row.put("spotUnit", spot.getSpotUnit());
        OriginDict origin = spot.getOriginDict();
        if (origin != null) {
            row.put("originName", origin.getOriginName());
        } else {
            row.put("originId", spot.getOriginId());
        }
        Date updateTime = spot.getUpdateTime();
        row.put("updateTime", updateTime == null ? null : updateTime.toString());
        return row;
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source != null && source.toLowerCase().contains(keyword.toLowerCase());
    }
}
