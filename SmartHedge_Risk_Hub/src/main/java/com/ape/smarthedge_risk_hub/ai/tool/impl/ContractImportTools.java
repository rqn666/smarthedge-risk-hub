package com.ape.smarthedge_risk_hub.ai.tool.impl;

import com.ape.smarthedge_risk_hub.ai.tool.Tool;
import com.ape.smarthedge_risk_hub.ai.tool.ToolParam;
import com.ape.smarthedge_risk_hub.dao.ExchangeMapper;
import com.ape.smarthedge_risk_hub.dao.FuturesContractMapper;
import com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper;
import com.ape.smarthedge_risk_hub.pojo.Exchange;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractTextAddDTO;
import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import com.ape.smarthedge_risk_hub.service.IFuturesContractTextService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约条款「文本 → 结构化 → 入库」工具。
 *
 * <p>和查询类工具相比，这是本项目里<b>唯一带写操作的 AI 能力</b>，所以设计上多了三道闸门：
 *
 * <ol>
 *   <li><b>拆成 preview / save 两步</b>：模型只能先预览，用户确认后才允许保存。</li>
 *   <li><b>服务端强制令牌</b>：save 必须携带 preview 发放的一次性 token，
 *       服务端只从令牌缓存里取字段入库。这样"模型跳过预览直接写库"在服务端就不可能发生 ——
 *       不依赖模型自觉遵守提示词。</li>
 *   <li><b>字典校验</b>：品种、交易所、合约代码必须能在系统里匹配到。
 *       既防脏数据，也是必需 —— 底层 insertText 依赖这些关联 id，匹配不上会直接 NPE。</li>
 * </ol>
 *
 * <p>另一个细节：save 只有两个参数（token + contractCode），字段全部来自服务端缓存。
 * 这样能保证<b>「用户确认的字段」与「实际入库的字段」严格一致</b>，
 * 避免模型在第二轮调用时把字段抄错（7B 模型抄 14 个字段出错率不低）。
 */
@Component
public class ContractImportTools {

    private static final Logger log = LoggerFactory.getLogger(ContractImportTools.class);

    /** 预览令牌有效期。给用户留出确认时间，又不至于长期占内存 */
    private static final long TOKEN_TTL_MS = 10 * 60 * 1000L;

    /** previewToken → 待确认字段。服务端侧的"待办"，save 只认这里的数据 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<String, Pending>();

    @Autowired
    private IFuturesContractTextService futuresContractTextService;

    @Autowired
    private FuturesContractMapper futuresContractMapper;

    @Autowired
    private TradingVarietyMapper tradingVarietyMapper;

    @Autowired
    private ExchangeMapper exchangeMapper;

    /**
     * 预览：校验 + 返回结构化字段，不写库。
     * 模型从用户给的文本里抽字段，填进来即可 —— 抽取动作由大模型完成，本工具负责把关。
     */
    @Tool(name = "previewFuturesContractText",
            description = "解析用户提供的期货合约条款文本，抽取结构化字段并返回预览结果，绝不写入数据库。"
                    + "当用户发来一段合约条款文字、要求录入/新增/解析合约条款时，必须先调用本工具。"
                    + "本工具会校验品种、交易所、合约代码是否存在于系统中，并列出缺失字段；"
                    + "校验通过会返回一个 previewToken，用户确认字段无误后再用它调用 saveFuturesContractText。")
    public Map<String, Object> preview(
            @ToolParam("合约代码，如 RB2410；从文本中提取，没有则留空") String contractCode,
            @ToolParam("品种名称，如 螺纹钢") String varietyName,
            @ToolParam("品种代码，如 RB") String varietyCode,
            @ToolParam("交易所名称，如 上海期货交易所") String exchangeName,
            @ToolParam(value = "所属行业，如 黑色金属", required = false) String industryName,
            @ToolParam(value = "交易单位，如 10吨/手", required = false) String tradeUnit,
            @ToolParam(value = "报价单位，如 元/吨", required = false) String quoteUnit,
            @ToolParam(value = "最小变动价位，纯数字，如 1", required = false) String minPriceMove,
            @ToolParam(value = "涨跌停板幅度，如 上一交易日结算价的±4%", required = false) String limitRange,
            @ToolParam(value = "合约月份，如 1,3,5,7,9,11月", required = false) String contractMonths,
            @ToolParam(value = "交易时间，如 上午9:00-11:30", required = false) String tradeTime,
            @ToolParam(value = "最后交易日规则，如 合约月份第15个交易日", required = false) String lastTradingDayRule,
            @ToolParam(value = "交割日期规则", required = false) String deliveryDateRule,
            @ToolParam(value = "最低交易保证金，如 合约价值的7%", required = false) String minMargin) {

        Fields fields = new Fields(contractCode, varietyName, varietyCode, exchangeName, industryName,
                tradeUnit, quoteUnit, minPriceMove, limitRange, contractMonths,
                tradeTime, lastTradingDayRule, deliveryDateRule, minMargin);
        return check(fields);
    }

    /**
     * 保存：只能凭 preview 发放的令牌调用。
     * 字段不从参数取，而是从服务端令牌缓存取 —— 保证入库内容与用户确认过的预览完全一致。
     */
    @Tool(name = "saveFuturesContractText",
            description = "把用户在预览中已确认的期货合约条款保存入库。"
                    + "必须传入 previewFuturesContractText 返回的 previewToken；"
                    + "严禁在用户明确确认预览结果之前调用本工具。")
    public Map<String, Object> save(
            @ToolParam("previewFuturesContractText 返回的 previewToken，原样传入") String previewToken,
            @ToolParam(value = "合约代码，用于二次确认保存对象，需与预览时一致", required = false) String contractCode) {

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("stage", "save");

        // 顺手清理过期令牌，避免长期占内存
        purgeExpired();

        Pending item = previewToken == null ? null : pending.get(previewToken.trim());
        if (item == null) {
            result.put("success", false);
            result.put("message", "previewToken 无效或已过期（有效期 10 分钟）。"
                    + "请重新调用 previewFuturesContractText 生成预览，拿到新的 previewToken 后再保存。");
            return result;
        }
        if (!isBlank(contractCode) && !contractCode.trim().equalsIgnoreCase(item.fields.contractCode)) {
            result.put("success", false);
            result.put("message", "合约代码与预览不一致：预览的是「" + item.fields.contractCode
                    + "」，本次传入「" + contractCode + "」。请确认要保存的对象。");
            return result;
        }

        // 令牌一次性：用掉就删，防止重复提交造成重复数据
        pending.remove(previewToken.trim());

        FuturesContractTextAddDTO dto = toDto(item);
        int rows;
        try {
            rows = futuresContractTextService.insertText(dto);
        } catch (Exception e) {
            log.warn("[ai] 合约条款保存失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "写入数据库失败：" + e.getMessage());
            return result;
        }

        result.put("success", rows > 0);
        result.put("insertedRows", rows);
        result.put("contractCode", item.fields.contractCode);
        // 措辞刻意不写"已同步到 ES"：双写是事务提交后异步执行的，ES 不可用时只入重试队列，
        // 这里无法断言同步结果。AI 的工具返回必须是可验证的事实，不能给用户过度承诺。
        result.put("message", rows > 0
                ? "已成功写入 1 条期货合约条款（品种 " + item.fields.varietyName
                  + "，合约 " + item.fields.contractCode + "）。"
                  + "ES 检索索引由双写链路在事务提交后异步同步，同步失败会自动进入重试队列。"
                : "写入数据库失败，未产生数据，请检查字段后重试。");
        return result;
    }

    // ======================= 内部实现 =======================

    private Map<String, Object> check(Fields f) {
        List<String> missing = new ArrayList<String>();
        Map<String, Object> dictErrors = new LinkedHashMap<String, Object>();

        if (isBlank(f.contractCode)) {
            missing.add("contractCode（合约代码）");
        }
        if (isBlank(f.varietyName)) {
            missing.add("varietyName（品种名称）");
        }
        if (isBlank(f.varietyCode)) {
            missing.add("varietyCode（品种代码）");
        }
        if (isBlank(f.exchangeName)) {
            missing.add("exchangeName（交易所名称）");
        }
        if (isBlank(f.tradeUnit)) {
            missing.add("tradeUnit（交易单位）");
        }
        if (isBlank(f.minMargin)) {
            missing.add("minMargin（最低交易保证金）");
        }
        if (!isBlank(f.minPriceMove) && !isNumeric(f.minPriceMove)) {
            dictErrors.put("minPriceMove", "最小变动价位必须是纯数字，当前为「" + f.minPriceMove + "」");
        }

        // 字典校验：必须能匹配到已有数据，否则 insertText 会因关联 id 为空而失败
        if (!isBlank(f.contractCode)) {
            FuturesContract contract = futuresContractMapper.selectOne(
                    Wrappers.<FuturesContract>lambdaQuery()
                            .eq(FuturesContract::getContractCode, f.contractCode.trim())
                            .last("limit 1"));
            if (contract == null) {
                dictErrors.put("contractCode",
                        "系统中不存在合约代码「" + f.contractCode + "」，请先维护该合约，或改用已有合约代码");
            }
        }
        if (!isBlank(f.varietyName) && !isBlank(f.varietyCode)) {
            TradingVariety variety = tradingVarietyMapper.selectOne(
                    Wrappers.<TradingVariety>lambdaQuery()
                            .eq(TradingVariety::getVarietyName, f.varietyName.trim())
                            .eq(TradingVariety::getVarietyCode, f.varietyCode.trim())
                            .last("limit 1"));
            if (variety == null) {
                dictErrors.put("variety",
                        "品种「" + f.varietyName + "(" + f.varietyCode + ")」不存在或名称与代码不匹配。已维护的品种：" + availableVarieties());
            }
        }
        if (!isBlank(f.exchangeName)) {
            Exchange exchange = exchangeMapper.selectOne(
                    Wrappers.<Exchange>lambdaQuery()
                            .eq(Exchange::getExchangeName, f.exchangeName.trim())
                            .last("limit 1"));
            if (exchange == null) {
                dictErrors.put("exchangeName",
                        "交易所「" + f.exchangeName + "」不存在。已维护的交易所：" + availableExchanges());
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("stage", "preview");
        result.put("fields", f.toMap());
        result.put("missingRequired", missing);
        result.put("dictErrors", dictErrors);

        if (!missing.isEmpty() || !dictErrors.isEmpty()) {
            result.put("success", false);
            result.put("message", "校验未通过：请补齐 missingRequired 中的字段、修正 dictErrors 中的问题后重新预览。本次未写入数据库。");
            return result;
        }

        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pending.put(token, new Pending(f, System.currentTimeMillis()));
        result.put("success", true);
        result.put("previewToken", token);
        result.put("message", "校验通过。请把以上 fields 展示给用户确认；用户确认无误后，"
                + "调用 saveFuturesContractText 并传入 previewToken=" + token + " 完成保存。");
        return result;
    }

    private FuturesContractTextAddDTO toDto(Pending item) {
        Fields f = item.fields;
        FuturesContractTextAddDTO dto = new FuturesContractTextAddDTO();
        FuturesContract contract = futuresContractMapper.selectOne(
                Wrappers.<FuturesContract>lambdaQuery()
                        .eq(FuturesContract::getContractCode, f.contractCode.trim())
                        .last("limit 1"));
        dto.setContractId(contract == null ? 0 : contract.getContractId());
        dto.setVarietyName(f.varietyName.trim());
        dto.setVarietyCode(f.varietyCode.trim());
        dto.setExchangeName(f.exchangeName.trim());
        dto.setIndustryName(isBlank(f.industryName) ? null : f.industryName.trim());
        dto.setTradeUnit(trim(f.tradeUnit));
        dto.setQuoteUnit(trim(f.quoteUnit));
        dto.setMinPriceMove(parseDouble(f.minPriceMove));
        dto.setLimitRange(trim(f.limitRange));
        dto.setContractMonths(trim(f.contractMonths));
        dto.setTradeTime(trim(f.tradeTime));
        dto.setLastTradingDayRule(trim(f.lastTradingDayRule));
        dto.setDeliveryDateRule(trim(f.deliveryDateRule));
        dto.setMinMargin(trim(f.minMargin));
        return dto;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            if (now - e.getValue().createdAt > TOKEN_TTL_MS) {
                pending.remove(e.getKey());
            }
        }
    }

    private String availableVarieties() {
        try {
            List<TradingVariety> list = tradingVarietyMapper.selectList(
                    Wrappers.<TradingVariety>lambdaQuery().last("limit 30"));
            StringBuilder sb = new StringBuilder();
            for (TradingVariety v : list) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(v.getVarietyName()).append("(").append(v.getVarietyCode()).append(")");
            }
            return sb.length() == 0 ? "（品种表为空）" : sb.toString();
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }

    private String availableExchanges() {
        try {
            List<Exchange> list = exchangeMapper.selectList(
                    Wrappers.<Exchange>lambdaQuery().last("limit 30"));
            StringBuilder sb = new StringBuilder();
            for (Exchange x : list) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(x.getExchangeName());
            }
            return sb.length() == 0 ? "（交易所表为空）" : sb.toString();
        } catch (Exception e) {
            return "（读取失败：" + e.getMessage() + "）";
        }
    }

    private boolean isNumeric(String s) {
        return s != null && s.trim().matches("-?\\d+(\\.\\d+)?");
    }

    private double parseDouble(String s) {
        if (!isNumeric(s)) {
            return 0.0d;
        }
        return Double.parseDouble(s.trim());
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 待确认的字段 + 发放时间 */
    private static class Pending {
        final Fields fields;
        final long createdAt;

        Pending(Fields fields, long createdAt) {
            this.fields = fields;
            this.createdAt = createdAt;
        }
    }

    /** 抽取出来的合约条款字段。preview 与 save 共用同一份结构 */
    private static class Fields {
        String contractCode;
        String varietyName;
        String varietyCode;
        String exchangeName;
        String industryName;
        String tradeUnit;
        String quoteUnit;
        String minPriceMove;
        String limitRange;
        String contractMonths;
        String tradeTime;
        String lastTradingDayRule;
        String deliveryDateRule;
        String minMargin;

        Fields(String contractCode, String varietyName, String varietyCode, String exchangeName,
               String industryName, String tradeUnit, String quoteUnit, String minPriceMove,
               String limitRange, String contractMonths, String tradeTime,
               String lastTradingDayRule, String deliveryDateRule, String minMargin) {
            this.contractCode = contractCode;
            this.varietyName = varietyName;
            this.varietyCode = varietyCode;
            this.exchangeName = exchangeName;
            this.industryName = industryName;
            this.tradeUnit = tradeUnit;
            this.quoteUnit = quoteUnit;
            this.minPriceMove = minPriceMove;
            this.limitRange = limitRange;
            this.contractMonths = contractMonths;
            this.tradeTime = tradeTime;
            this.lastTradingDayRule = lastTradingDayRule;
            this.deliveryDateRule = deliveryDateRule;
            this.minMargin = minMargin;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("contractCode", contractCode);
            m.put("varietyName", varietyName);
            m.put("varietyCode", varietyCode);
            m.put("exchangeName", exchangeName);
            m.put("industryName", industryName);
            m.put("tradeUnit", tradeUnit);
            m.put("quoteUnit", quoteUnit);
            m.put("minPriceMove", minPriceMove);
            m.put("limitRange", limitRange);
            m.put("contractMonths", contractMonths);
            m.put("tradeTime", tradeTime);
            m.put("lastTradingDayRule", lastTradingDayRule);
            m.put("deliveryDateRule", deliveryDateRule);
            m.put("minMargin", minMargin);
            return m;
        }
    }
}
