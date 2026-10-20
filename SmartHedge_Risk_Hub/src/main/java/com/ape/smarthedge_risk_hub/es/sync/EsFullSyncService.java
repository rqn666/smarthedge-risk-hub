package com.ape.smarthedge_risk_hub.es.sync;

import com.ape.smarthedge_risk_hub.es.config.EsProperties;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsDocConverter;
import com.ape.smarthedge_risk_hub.es.core.EsDocumentService;
import com.ape.smarthedge_risk_hub.es.core.EsIndexManager;
import com.ape.smarthedge_risk_hub.es.model.EsDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量同步：把 MySQL 的整表数据灌进 ES。两个用途：
 * 1) 首次上线初始化索引数据（手动触发一次）
 * 2) 数据漂移后排障修复（重建索引）
 *
 * 各表的 SELECT 带上 left join，把品种名/交易所名/行业名/经营者名补进来，
 * 否则 ES 只有 id 没有名称，用户搜"螺纹钢"搜不到。
 */
@Service
public class EsFullSyncService {

    private static final Logger log = LoggerFactory.getLogger(EsFullSyncService.class);

    /** 每批写入条数，避免一次性 bulk 太大把 ES 打爆 */
    private static final int BATCH_SIZE = 500;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EsDocumentService documentService;

    @Autowired
    private EsDocConverter docConverter;

    @Autowired
    private EsIndexManager indexManager;

    @Autowired
    private EsProperties props;

    /**
     * 全量同步单个索引。
     *
     * @param index        目标索引
     * @param recreate     是否先删索引重建（true 用于彻底修复；false 增量覆盖写入）
     * @return 同步结果统计
     */
    public Map<String, Object> syncIndex(EsIndex index, boolean recreate) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("index", index.getIndexName());
        result.put("label", index.getLabel());

        if (!props.isEnabled()) {
            result.put("success", false);
            result.put("message", "ES 未启用（es.enabled=false），已跳过");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            if (recreate) {
                indexManager.delete(index);
            }
            indexManager.createIfAbsent(index);

            List<Map<String, Object>> rows = queryAll(index);
            result.put("mysqlRows", rows.size());

            List<EsDoc> batch = new ArrayList<EsDoc>(BATCH_SIZE);
            int success = 0;
            int skipped = 0;
            for (Map<String, Object> row : rows) {
                EsDoc doc = docConverter.fromRow(index, row);
                if (doc == null || doc.isEmpty()) {
                    skipped++;
                    continue;
                }
                batch.add(doc);
                if (batch.size() >= BATCH_SIZE) {
                    success += documentService.saveBatch(index, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                success += documentService.saveBatch(index, batch);
            }

            // 必须 refresh，否则刚写入的文档还没进可搜索段，count 会读到旧值
            documentService.refresh(index);
            long esCount = documentService.count(index);
            result.put("esDocs", esCount);
            result.put("written", success);
            result.put("skipped", skipped);
            result.put("costMs", System.currentTimeMillis() - start);
            // 一致性校验：写入条数与 ES 实际文档数应相等
            // 注意用 written 而不是 mysqlRows：失败条数不应计入期望值
            result.put("consistent", (long) success == esCount);
            result.put("success", true);
            log.info("[es] 全量同步完成 {}: mysql={} es={} 耗时={}ms",
                    index.getIndexName(), rows.size(), esCount, result.get("costMs"));
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
            log.error("[es] 全量同步失败 {}: {}", index.getIndexName(), e.getMessage(), e);
        }
        return result;
    }

    /** 全量同步所有索引 */
    public List<Map<String, Object>> syncAll(boolean recreate) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (EsIndex index : EsIndex.all()) {
            list.add(syncIndex(index, recreate));
        }
        return list;
    }

    /**
     * 查单条（含 join 补名），补偿任务重建文档时用。
     * 与 syncIndex 共用 SQL，只是加了 where 条件。
     */
    public EsDoc loadOne(EsIndex index, String bizId) {
        String sql = selectSql(index) + " where t." + index.getIdColumn() + " = ?";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, bizId);
        } catch (Exception e) {
            log.warn("[es] 补偿反查失败 {}/{}: {}", index.getIndexName(), bizId, e.getMessage());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        return docConverter.fromRow(index, rows.get(0));
    }

    /** 各索引对应的全量查询 SQL */
    private List<Map<String, Object>> queryAll(EsIndex index) {
        return jdbcTemplate.queryForList(selectSql(index));
    }

    /**
     * 生成带别名 t 的查询 SQL，字段名统一用下划线别名，
     * 保证 docConverter.fromRow 能按 snake_case 取到值。
     */
    private String selectSql(EsIndex index) {
        switch (index) {
            case FUTURES_CONTRACT:
                return "select t.contract_id as contract_id, t.contract_code as contract_code, "
                        + "t.contract_name as contract_name, t.variety_id as variety_id, "
                        + "t.last_trading_day as last_trading_day, t.update_method as update_method, "
                        + "t.update_time as update_time, t.create_time as create_time, "
                        + "v.variety_name as variety_name, v.variety_code as variety_code, "
                        + "v.exchange_id as exchange_id, e.exchange_name as exchange_name "
                        + "from futures_contract t "
                        + "left join trading_variety v on v.variety_id = t.variety_id "
                        + "left join exchange e on e.exchange_id = v.exchange_id";

            case FUTURES_CONTRACT_TEXT:
                return "select t.text_id as text_id, t.contract_id as contract_id, "
                        + "t.variety_id as variety_id, t.exchange_id as exchange_id, "
                        + "t.trade_unit as trade_unit, t.quote_unit as quote_unit, "
                        + "t.min_price_move as min_price_move, t.limit_range as limit_range, "
                        + "t.contract_months as contract_months, t.trade_time as trade_time, "
                        + "t.last_trading_day_rule as last_trading_day_rule, "
                        + "t.delivery_date_rule as delivery_date_rule, t.min_margin as min_margin, "
                        + "t.update_time as update_time, t.create_time as create_time, "
                        + "v.variety_name as variety_name, v.variety_code as variety_code, "
                        + "e.exchange_name as exchange_name, i.industry_name as industry_name "
                        + "from futures_contract_text t "
                        + "left join trading_variety v on v.variety_id = t.variety_id "
                        + "left join exchange e on e.exchange_id = t.exchange_id "
                        + "left join industry i on i.industry_id = v.industry_id";

            case OPTIONS_CONTRACT:
                return "select t.option_id as option_id, t.option_code as option_code, "
                        + "t.option_name as option_name, t.variety_id as variety_id, "
                        + "t.underlying_contract_id as underlying_contract_id, "
                        + "t.option_type as option_type, t.strike_price as strike_price, "
                        + "t.last_trading_day as last_trading_day, t.update_method as update_method, "
                        + "t.update_time as update_time, t.create_time as create_time, "
                        + "v.variety_name as variety_name, v.variety_code as variety_code, "
                        + "v.exchange_id as exchange_id, e.exchange_name as exchange_name "
                        + "from options_contract t "
                        + "left join trading_variety v on v.variety_id = t.variety_id "
                        + "left join exchange e on e.exchange_id = v.exchange_id";

            case OPTIONS_CONTRACT_TEXT:
                return "select t.text_id as text_id, t.option_id as option_id, "
                        + "t.variety_id as variety_id, t.exchange_id as exchange_id, "
                        + "t.underlying_asset as underlying_asset, t.option_type as option_type, "
                        + "t.trade_unit as trade_unit, t.quote_unit as quote_unit, "
                        + "t.min_price_move as min_price_move, t.limit_range as limit_range, "
                        + "t.contract_months as contract_months, t.trade_time as trade_time, "
                        + "t.last_trading_day_rule as last_trading_day_rule, "
                        + "t.expiry_date_rule as expiry_date_rule, t.strike_price_rule as strike_price_rule, "
                        + "t.exercise_method as exercise_method, t.trade_code as trade_code, "
                        + "t.update_time as update_time, t.create_time as create_time, "
                        + "v.variety_name as variety_name, v.variety_code as variety_code, "
                        + "e.exchange_name as exchange_name, i.industry_name as industry_name "
                        + "from options_contract_text t "
                        + "left join trading_variety v on v.variety_id = t.variety_id "
                        + "left join exchange e on e.exchange_id = t.exchange_id "
                        + "left join industry i on i.industry_id = v.industry_id";

            case SUPPLIER:
                return "select t.supplier_id as supplier_id, t.supplier_name as supplier_name, "
                        + "t.contact_phone as contact_phone, t.address as address, "
                        + "t.credit_code as credit_code, t.operator_id as operator_id, "
                        + "t.score as score, t.status as status, "
                        + "t.update_time as update_time, t.create_time as create_time, "
                        + "o.operator_name as operator_name "
                        + "from supplier t left join operator o on o.operator_id = t.operator_id";

            default:
                throw new IllegalArgumentException("未定义全量同步 SQL: " + index);
        }
    }
}
