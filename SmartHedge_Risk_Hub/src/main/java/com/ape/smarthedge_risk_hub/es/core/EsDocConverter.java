package com.ape.smarthedge_risk_hub.es.core;

import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.model.EsDoc;
import com.ape.smarthedge_risk_hub.pojo.FuturesContract;
import com.ape.smarthedge_risk_hub.pojo.FuturesContractText;
import com.ape.smarthedge_risk_hub.pojo.Operator;
import com.ape.smarthedge_risk_hub.pojo.OptionsContract;
import com.ape.smarthedge_risk_hub.pojo.OptionsContractText;
import com.ape.smarthedge_risk_hub.pojo.Supplier;
import com.ape.smarthedge_risk_hub.pojo.TradingVariety;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 业务对象 -> EsDoc 转换。
 *
 * 两个入口：
 * 1) fromEntity：双写时用，业务 Service 写完 MySQL 后把 POJO 交进来（已把名称维度填好）
 * 2) fromRow   ：全量同步/补偿时用，JdbcTemplate 查出的行（列名是下划线）转成文档
 *
 * 为什么不用 Jackson 直接序列化 POJO？
 * POJO 里有 sub-object（tradingVariety/operator）和 ES 未定义的字段，
 * 直接序列化会出现动态字段、类型冲突（date 字段被写成字符串等）。
 * 走这里逐个字段显式搬运，才能保证 ES 里字段类型稳定。
 */
@Component
public class EsDocConverter {

    public EsDoc fromEntity(EsIndex index, Object entity) {
        if (entity == null) {
            return null;
        }
        EsDoc doc;
        if (entity instanceof FuturesContract) {
            doc = futuresContract((FuturesContract) entity);
        } else if (entity instanceof FuturesContractText) {
            doc = futuresContractText((FuturesContractText) entity);
        } else if (entity instanceof OptionsContract) {
            doc = optionsContract((OptionsContract) entity);
        } else if (entity instanceof OptionsContractText) {
            doc = optionsContractText((OptionsContractText) entity);
        } else if (entity instanceof Supplier) {
            doc = supplier((Supplier) entity);
        } else {
            throw new IllegalArgumentException("不支持的实体类型: " + entity.getClass().getName()
                    + "，请在 EsDocConverter 中补充转换分支");
        }
        return withAlias(index, doc);
    }

    /**
     * 把字段中文别名拼成 __alias 文本塞进文档。
     * 效果：用户搜"保证金"时，即使 minMargin 的值是"合约价值的6%"（不含这三个字），
     * 也能因为 __alias 里含"保证金"而命中。
     */
    private EsDoc withAlias(EsIndex index, EsDoc doc) {
        if (doc == null || index.getAliases().isEmpty()) {
            return doc;
        }
        String aliasText = index.buildAliasText();
        if (!aliasText.isEmpty()) {
            doc.put(EsIndex.ALIAS_FIELD, aliasText);
        }
        return doc;
    }

    private EsDoc futuresContract(FuturesContract c) {
        EsDoc doc = EsDoc.of(c.getContractId());
        doc.put("contractId", (long) c.getContractId())
                .put("contractCode", c.getContractCode())
                .put("contractName", c.getContractName())
                .put("lastTradingDay", c.getLastTradingDay())
                .put("updateMethod", c.getUpdateMethod())
                .put("updateTime", c.getUpdateTime())
                .put("createTime", c.getCreateTime());
        TradingVariety v = c.getTradingVariety();
        if (v != null) {
            doc.put("varietyId", (long) v.getVarietyId())
                    .put("varietyName", v.getVarietyName())
                    .put("varietyCode", v.getVarietyCode());
            if (v.getExchange() != null) {
                doc.put("exchangeId", (long) v.getExchange().getExchangeId())
                        .put("exchangeName", v.getExchange().getExchangeName());
            } else {
                doc.put("exchangeId", (long) v.getExchangeId());
            }
        }
        return doc;
    }

    private EsDoc futuresContractText(FuturesContractText t) {
        EsDoc doc = EsDoc.of(t.getTextId());
        doc.put("textId", (long) t.getTextId())
                .put("contractId", (long) t.getContractId())
                .put("varietyId", (long) t.getVarietyId())
                .put("exchangeId", (long) t.getExchangeId())
                .put("tradeUnit", t.getTradeUnit())
                .put("quoteUnit", t.getQuoteUnit())
                .put("minPriceMove", t.getMinPriceMove())
                .put("limitRange", t.getLimitRange())
                .put("contractMonths", t.getContractMonths())
                .put("tradeTime", t.getTradeTime())
                .put("lastTradingDayRule", t.getLastTradingDayRule())
                .put("deliveryDateRule", t.getDeliveryDateRule())
                .put("minMargin", t.getMinMargin())
                .put("updateTime", t.getUpdateTime())
                .put("createTime", t.getCreateTime());
        TradingVariety v = t.getTradingVariety();
        if (v != null) {
            doc.put("varietyName", v.getVarietyName()).put("varietyCode", v.getVarietyCode());
        }
        if (t.getExchange() != null) {
            doc.put("exchangeName", t.getExchange().getExchangeName());
        }
        // 注意：FuturesContractText 实体没有 industry 字段，industryName 只能靠联表补
        // （见 EsFullSyncService 的 SQL），这里不做处理
        return doc;
    }

    private EsDoc optionsContract(OptionsContract c) {
        EsDoc doc = EsDoc.of(c.getOptionId());
        doc.put("optionId", (long) c.getOptionId())
                .put("optionCode", c.getOptionCode())
                .put("optionName", c.getOptionName())
                .put("varietyId", (long) c.getVarietyId())
                .put("underlyingContractId", (long) c.getUnderlyingContractId())
                .put("optionType", c.getOptionType())
                .put("strikePrice", c.getStrikePrice())
                .put("lastTradingDay", c.getLastTradingDay())
                .put("updateMethod", c.getUpdateMethod())
                .put("updateTime", c.getUpdateTime())
                .put("createTime", c.getCreateTime());
        TradingVariety v = c.getTradingVariety();
        if (v != null) {
            doc.put("varietyName", v.getVarietyName()).put("varietyCode", v.getVarietyCode());
            if (v.getExchange() != null) {
                doc.put("exchangeId", (long) v.getExchange().getExchangeId())
                        .put("exchangeName", v.getExchange().getExchangeName());
            }
        }
        return doc;
    }

    private EsDoc optionsContractText(OptionsContractText t) {
        EsDoc doc = EsDoc.of(t.getTextId());
        doc.put("textId", (long) t.getTextId())
                .put("optionId", (long) t.getOptionId())
                .put("varietyId", (long) t.getVarietyId())
                .put("exchangeId", (long) t.getExchangeId())
                .put("underlyingAsset", t.getUnderlyingAsset())
                .put("optionType", t.getOptionType())
                .put("tradeUnit", t.getTradeUnit())
                .put("quoteUnit", t.getQuoteUnit())
                .put("minPriceMove", t.getMinPriceMove())
                .put("limitRange", t.getLimitRange())
                .put("contractMonths", t.getContractMonths())
                .put("tradeTime", t.getTradeTime())
                .put("lastTradingDayRule", t.getLastTradingDayRule())
                .put("expiryDateRule", t.getExpiryDateRule())
                .put("strikePriceRule", t.getStrikePriceRule())
                .put("exerciseMethod", t.getExerciseMethod())
                .put("tradeCode", t.getTradeCode())
                .put("updateTime", t.getUpdateTime())
                .put("createTime", t.getCreateTime());
        TradingVariety v = t.getTradingVariety();
        if (v != null) {
            doc.put("varietyName", v.getVarietyName()).put("varietyCode", v.getVarietyCode());
        }
        if (t.getExchange() != null) {
            doc.put("exchangeName", t.getExchange().getExchangeName());
        }
        return doc;
    }

    private EsDoc supplier(Supplier s) {
        EsDoc doc = EsDoc.of(s.getSupplierId());
        doc.put("supplierId", (long) s.getSupplierId())
                .put("supplierName", s.getSupplierName())
                .put("contactPhone", s.getContactPhone())
                .put("address", s.getAddress())
                .put("creditCode", s.getCreditCode())
                .put("operatorId", (long) s.getOperatorId())
                .put("score", s.getScore())
                .put("status", s.getStatus())
                .put("updateTime", s.getUpdateTime())
                .put("createTime", s.getCreateTime());
        Operator op = s.getOperator();
        if (op != null) {
            doc.put("operatorName", op.getOperatorName());
        }
        return doc;
    }

    /**
     * 从 JdbcTemplate 查出的行构造文档。
     * 行里的列名是下划线风格，索引里的字段名是驼峰，需要转。同时补默认值防止类型冲突。
     */
    public EsDoc fromRow(EsIndex index, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object id = null;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(index.getIdColumn())) {
                id = e.getValue();
                break;
            }
        }
        if (id == null) {
            return null;
        }
        EsDoc doc = EsDoc.of(id);
        for (Map.Entry<String, String> field : index.getFields().entrySet()) {
            String camel = field.getKey();
            String snake = EsIndex.camelToSnake(camel);
            Object value = row.get(snake);
            if (value == null) {
                value = row.get(camel);
            }
            doc.put(camel, value);
        }
        return withAlias(index, doc);
    }
}
