package com.ape.smarthedge_risk_hub.ai.tool.impl;

import com.ape.smarthedge_risk_hub.ai.tool.Tool;
import com.ape.smarthedge_risk_hub.ai.tool.ToolParam;
import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractText;
import com.ape.smarthedge_risk_hub.service.IFuturesContractService;
import com.ape.smarthedge_risk_hub.service.IFuturesContractTextService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 期货合约相关工具。全部复用现有 Service，不新增 SQL。
 */
@Component
public class FuturesContractTools {

    private static final long MAX_ROWS = 10L;

    @Autowired
    private IFuturesContractTextService futuresContractTextService;

    @Autowired
    private IFuturesContractService futuresContractService;

    /**
     * 查合约条款：交易单位、涨跌停板、最低保证金、最后交易日规则等。
     * 走现成的 findByPage(keyword)，支持品种名/合约代码/交易所/行业名模糊匹配。
     */
    @Tool(name = "queryFuturesContractSpec",
            description = "查询期货合约的条款详情，包括交易单位、报价单位、最小变动价位、涨跌停板幅度、"
                    + "最低交易保证金、合约月份、交易时间、最后交易日规则、交割日期规则。"
                    + "当用户询问某期货品种或合约的保证金比例、涨跌停板、交易单位、最后交易日等条款时调用。"
                    + "参数可以是品种名（如 螺纹钢、铁矿石）或合约代码（如 RB2410、CU2408）。")
    public Map<String, Object> queryContractSpec(
            @ToolParam("品种名或合约代码，如 螺纹钢、RB2410、铁矿石") String keyWord) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (isBlank(keyWord)) {
            result.put("error", "参数 keyWord 不能为空，请传入品种名或合约代码");
            return result;
        }
        IPage<FuturesContractText> found = futuresContractTextService.findByPage(requestParam(1L, keyWord.trim()));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (found != null && found.getRecords() != null) {
            for (FuturesContractText text : found.getRecords()) {
                rows.add(toSpecRow(text));
            }
        }
        result.put("keyWord", keyWord.trim());
        result.put("total", found == null ? 0L : found.getTotal());
        result.put("count", rows.size());
        result.put("records", rows);
        if (rows.isEmpty()) {
            result.put("message", "未查询到与「" + keyWord.trim() + "」匹配的期货合约条款");
        }
        return result;
    }

    /**
     * 查合约基本信息：合约代码、名称、最后交易日。
     */
    @Tool(name = "queryFuturesContract",
            description = "查询期货合约的基本信息列表，包括合约代码、合约名称、最后交易日、所属品种、所属交易所。"
                    + "当用户询问某个品种下有哪些合约、某合约的代码和名称、合约的最后交易日期、"
                    + "某品种在哪个交易所上市时调用。")
    public Map<String, Object> queryContract(
            @ToolParam(value = "品种名，如 螺纹钢、铁矿石、豆粕；不传则返回全部前若干条", required = false) String varietyName) {
        String keyword = isBlank(varietyName) ? "" : varietyName.trim();
        IPage<FuturesContract> found = futuresContractService.findAllByPage(requestParam(1L, ""), keyword);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (found != null && found.getRecords() != null) {
            for (FuturesContract contract : found.getRecords()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("contractCode", contract.getContractCode());
                row.put("contractName", contract.getContractName());
                row.put("lastTradingDay", formatDate(contract.getLastTradingDay()));
                if (contract.getTradingVariety() != null) {
                    row.put("varietyName", contract.getTradingVariety().getVarietyName());
                    // 交易所必须一并返回：否则模型问不到就按自己的先验知识编
                    // （实测把库里的郑州商品交易所答成了上海期货交易所）
                    if (contract.getTradingVariety().getExchange() != null) {
                        row.put("exchangeName", contract.getTradingVariety().getExchange().getExchangeName());
                    }
                }
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("varietyName", varietyName);
        result.put("total", found == null ? 0L : found.getTotal());
        result.put("count", rows.size());
        result.put("records", rows);
        if (rows.isEmpty()) {
            result.put("message", "未查询到匹配的期货合约");
        }
        return result;
    }

    private RequestParam requestParam(Long page, String keyword) {
        RequestParam requestParam = new RequestParam();
        requestParam.setPage(page);
        requestParam.setSize(MAX_ROWS);
        requestParam.setKeyword(keyword);
        return requestParam;
    }

    private Map<String, Object> toSpecRow(FuturesContractText text) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (text.getFuturesContract() != null) {
            row.put("contractCode", text.getFuturesContract().getContractCode());
            row.put("contractName", text.getFuturesContract().getContractName());
            row.put("lastTradingDay", formatDate(text.getFuturesContract().getLastTradingDay()));
        }
        if (text.getTradingVariety() != null) {
            row.put("varietyName", text.getTradingVariety().getVarietyName());
        }
        if (text.getExchange() != null) {
            row.put("exchangeName", text.getExchange().getExchangeName());
        }
        row.put("tradeUnit", text.getTradeUnit());
        row.put("quoteUnit", text.getQuoteUnit());
        row.put("minPriceMove", text.getMinPriceMove());
        row.put("limitRange", text.getLimitRange());
        row.put("contractMonths", text.getContractMonths());
        row.put("tradeTime", text.getTradeTime());
        row.put("lastTradingDayRule", text.getLastTradingDayRule());
        row.put("deliveryDateRule", text.getDeliveryDateRule());
        row.put("minMargin", text.getMinMargin());
        row.put("updateTime", formatDate(text.getUpdateTime()));
        return row;
    }

    private String formatDate(Date date) {
        return date == null ? null : new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
