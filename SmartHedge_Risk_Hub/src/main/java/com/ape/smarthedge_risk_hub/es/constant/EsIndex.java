package com.ape.smarthedge_risk_hub.es.constant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 索引定义。一个索引 = 一张业务表，一条文档 _id 直接用业务表主键，保证幂等（重复写入即覆盖）。
 *
 * 字段清单严格对齐 MySQL 实际列 + 联表补出的维度字段（品种名/交易所名/行业名），
 * 因为搜索场景下用户输的是"螺纹钢""上期所"，这些字段不冗余进来就搜不到。
 */
public enum EsIndex {

    /** 期货合约：字段对齐 futures_contract 表 + trading_variety / exchange 补名 */
    FUTURES_CONTRACT("futures_contract", "futures_contract",
            "contractId", "期货合约",
            fields(
                    f("contractId", "long"),
                    f("contractCode", "text"),
                    f("contractName", "text"),
                    f("varietyId", "long"),
                    f("varietyName", "text"),
                    f("varietyCode", "keyword"),
                    f("exchangeId", "long"),
                    f("exchangeName", "text"),
                    f("lastTradingDay", "date"),
                    f("updateMethod", "keyword"),
                    f("updateTime", "date"),
                    f("createTime", "date")
            )),

    /** 期货合约条款：对齐 futures_contract_text，联表补品种/交易所/行业名 */
    FUTURES_CONTRACT_TEXT("futures_contract_text", "futures_contract_text",
            "textId", "期货合约条款",
            fields(
                    f("textId", "long"),
                    f("contractId", "long"),
                    f("varietyId", "long"),
                    f("varietyName", "text"),
                    f("varietyCode", "keyword"),
                    f("exchangeId", "long"),
                    f("exchangeName", "text"),
                    f("industryName", "text"),
                    f("tradeUnit", "text"),
                    f("quoteUnit", "text"),
                    f("minPriceMove", "double"),
                    f("limitRange", "text"),
                    f("contractMonths", "text"),
                    f("tradeTime", "text"),
                    f("lastTradingDayRule", "text"),
                    f("deliveryDateRule", "text"),
                    f("minMargin", "text"),
                    f("updateTime", "date"),
                    f("createTime", "date")
            ),
            // 字段中文名并入搜索。原因：用户搜"保证金""交易单位""最后交易日"，
            // 这些词在字段值里并不出现（值形如"合约价值的6%"），只在字段名里，
            // 而 ES 的字段名本身不可搜。把中文别名拼成一段文本参与检索即可命中。
            aliases(
                    "tradeUnit=交易单位 合约单位 一手多少吨",
                    "quoteUnit=报价单位 计价单位",
                    "minPriceMove=最小变动价位 最小变动单位 一跳",
                    "limitRange=涨跌停板 涨跌幅限制 停板幅度",
                    "contractMonths=合约月份 交割月份 挂牌月份",
                    "tradeTime=交易时间 交易时段 开盘时间",
                    "lastTradingDayRule=最后交易日 最后交易日规则",
                    "deliveryDateRule=交割日期 交割规则",
                    "minMargin=保证金 最低保证金 保证金比例 保证金率",
                    "exchangeName=交易所 上期所 大商所 郑商所 中金所 广期所 上海期货交易所 大连商品交易所 郑州商品交易所 中国金融期货交易所 广州期货交易所",
                    "varietyName=品种 商品 合约品种"
            )),
    /** 期权合约：对齐 options_contract */
    OPTIONS_CONTRACT("options_contract", "options_contract",
            "optionId", "期权合约",
            fields(
                    f("optionId", "long"),
                    f("optionCode", "text"),
                    f("optionName", "text"),
                    f("varietyId", "long"),
                    f("varietyName", "text"),
                    f("varietyCode", "keyword"),
                    f("exchangeId", "long"),
                    f("exchangeName", "text"),
                    f("underlyingContractId", "long"),
                    f("optionType", "keyword"),
                    f("strikePrice", "double"),
                    f("lastTradingDay", "date"),
                    f("updateMethod", "keyword"),
                    f("updateTime", "date"),
                    f("createTime", "date")
            )),

    /** 期权合约条款：对齐 options_contract_text */
    OPTIONS_CONTRACT_TEXT("options_contract_text", "options_contract_text",
            "textId", "期权合约条款",
            fields(
                    f("textId", "long"),
                    f("optionId", "long"),
                    f("varietyId", "long"),
                    f("varietyName", "text"),
                    f("varietyCode", "keyword"),
                    f("exchangeId", "long"),
                    f("exchangeName", "text"),
                    f("industryName", "text"),
                    f("underlyingAsset", "text"),
                    f("optionType", "keyword"),
                    f("tradeUnit", "text"),
                    f("quoteUnit", "text"),
                    f("minPriceMove", "double"),
                    f("limitRange", "text"),
                    f("contractMonths", "text"),
                    f("tradeTime", "text"),
                    f("lastTradingDayRule", "text"),
                    f("expiryDateRule", "text"),
                    f("strikePriceRule", "text"),
                    f("exerciseMethod", "text"),
                    f("tradeCode", "text"),
                    f("updateTime", "date"),
                    f("createTime", "date")
            ),
            aliases(
                    "underlyingAsset=标的资产 标的物 挂钩标的",
                    "optionType=期权类型 看涨看跌 认购认沽",
                    "tradeUnit=交易单位 合约单位",
                    "quoteUnit=报价单位 计价单位",
                    "minPriceMove=最小变动价位 最小变动单位",
                    "limitRange=涨跌停板 涨跌幅限制",
                    "contractMonths=合约月份 到期月份",
                    "tradeTime=交易时间 交易时段",
                    "lastTradingDayRule=最后交易日 最后交易日规则",
                    "expiryDateRule=到期日 行权日 到期规则",
                    "strikePriceRule=行权价 执行价 敲定价",
                    "exerciseMethod=行权方式 履约方式 欧式美式",
                    "tradeCode=交易代码 合约代码"
            )),

    /** 供应商：对齐 supplier，联表补 operator 名称 */
    SUPPLIER("supplier", "supplier",
            "supplierId", "供应商",
            fields(
                    f("supplierId", "long"),
                    f("supplierName", "text"),
                    f("contactPhone", "keyword"),
                    f("address", "text"),
                    f("creditCode", "keyword"),
                    f("operatorId", "long"),
                    f("operatorName", "text"),
                    f("score", "integer"),
                    f("status", "integer"),
                    f("updateTime", "date"),
                    f("createTime", "date")
            ),
            aliases(
                    "contactPhone=联系电话 手机号 联系方式",
                    "address=地址 经营地址 注册地址",
                    "creditCode=统一社会信用代码 信用代码 税号",
                    "score=评分 信用评分 评级",
                    "status=状态 供应商状态",
                    "operatorName=经营者 操作人"
            ));

    /** ES 索引名 */
    private final String indexName;
    /** 对应 MySQL 表名 */
    private final String tableName;
    /** 主键属性名（用于写文档 _id 与反查 MySQL 主键列） */
    private final String idField;
    /** 中文描述 */
    private final String label;
    /** 字段定义：fieldName -> type，LinkedHashMap 保证顺序 */
    private final Map<String, String> fields;
    /**
     * 字段中文别名：fieldName -> "中文名 同义词..."。
     * 这些别名会拼成一段文本写入 __alias 字段参与检索，
     * 解决"用户搜字段中文名但字段值里没这个词"的问题。
     */
    private final Map<String, String> aliases;

    EsIndex(String indexName, String tableName, String idField, String label, Map<String, String> fields) {
        this(indexName, tableName, idField, label, fields, new LinkedHashMap<String, String>());
    }

    EsIndex(String indexName, String tableName, String idField, String label,
            Map<String, String> fields, Map<String, String> aliases) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.idField = idField;
        this.label = label;
        this.fields = fields;
        this.aliases = aliases;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getIdField() {
        return idField;
    }

    public String getLabel() {
        return label;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    /** 别名聚合字段名，写文档与建 mapping 都用它 */
    public static final String ALIAS_FIELD = "__alias";

    /**
     * 把别名拼成一段可检索文本，形如：
     * "保证金 最低保证金 保证金比例 交易单位 合约单位..."
     * 值里的中文名同样有价值（"螺纹钢"），但那些已在各自字段里，这里只放别名。
     */
    public String buildAliasText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            // "minMargin=保证金 最低保证金" -> 取等号左边也拼进去，让 "minMargin" 本身可搜
            sb.append(e.getKey()).append(' ').append(e.getValue()).append(' ');
        }
        return sb.toString().trim();
    }

    /** text 类型字段，搜索时 multi_match 的匹配目标（含别名聚合字段） */
    public List<String> getTextFields() {
        List<String> list = new ArrayList<String>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if ("text".equals(e.getValue())) {
                list.add(e.getKey());
            }
        }
        if (!aliases.isEmpty()) {
            list.add(ALIAS_FIELD);
        }
        return list;
    }

    /** 主键的数据库列名，如 contractId -> contract_id */
    public String getIdColumn() {
        return camelToSnake(idField);
    }

    public static EsIndex ofTable(String tableName) {
        for (EsIndex index : values()) {
            if (index.tableName.equalsIgnoreCase(tableName)) {
                return index;
            }
        }
        return null;
    }

    public static EsIndex ofIndexName(String indexName) {
        for (EsIndex index : values()) {
            if (index.indexName.equalsIgnoreCase(indexName)) {
                return index;
            }
        }
        return null;
    }

    public static List<EsIndex> all() {
        return Arrays.asList(values());
    }

    /** 全字段名列表，用于全量同步时拼 SQL 列 */
    public List<String> getFieldNames() {
        return new ArrayList<String>(fields.keySet());
    }

    public static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> fields(FieldDef... defs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (FieldDef def : defs) {
            map.put(def.name, def.type);
        }
        return map;
    }

    /**
     * 把 "field=中文名 同义词" 形式的字符串解析成 Map。
     * 传进来的每项形如 "minMargin=保证金 最低保证金 保证金比例"。
     */
    private static Map<String, String> aliases(String... items) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String item : items) {
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        return map;
    }

    private static FieldDef f(String name, String type) {
        return new FieldDef(name, type);
    }

    private static class FieldDef {
        final String name;
        final String type;

        FieldDef(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }
}
