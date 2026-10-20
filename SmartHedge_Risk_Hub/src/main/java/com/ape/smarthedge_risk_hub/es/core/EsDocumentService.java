package com.ape.smarthedge_risk_hub.es.core;

import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.model.EsDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 文档读写：单条/批量写入、按 id 删除、关键字搜索。
 * 所有写操作 _id 一律用业务主键，重复写即覆盖，天然幂等。
 */
@Component
public class EsDocumentService {

    private static final Logger log = LoggerFactory.getLogger(EsDocumentService.class);

    @Autowired
    private RestHighLevelClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 写单条 */
    public void save(EsIndex index, EsDoc doc) {
        if (doc == null || doc.isEmpty()) {
            log.warn("[es] {} 文档为空，跳过写入", index.getIndexName());
            return;
        }
        try {
            IndexRequest request = new IndexRequest(index.getIndexName())
                    .id(doc.getId())
                    .source(toJson(doc.getFields()), XContentType.JSON);
            client.index(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new IllegalStateException("写入 ES 失败: " + index.getIndexName() + "#" + doc.getId(), e);
        }
    }

    /** 批量写入，返回成功条数。单条失败不影响其他条 */
    public int saveBatch(EsIndex index, List<EsDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        try {
            BulkRequest bulk = new BulkRequest();
            for (EsDoc doc : docs) {
                if (doc == null || doc.isEmpty()) {
                    continue;
                }
                bulk.add(new IndexRequest(index.getIndexName())
                        .id(doc.getId())
                        .source(toJson(doc.getFields()), XContentType.JSON));
            }
            BulkResponse response = client.bulk(bulk, RequestOptions.DEFAULT);
            int failed = 0;
            if (response.hasFailures()) {
                for (BulkItemResponse item : response.getItems()) {
                    if (item.isFailed()) {
                        failed++;
                        log.warn("[es] 批量写入失败 id={}, reason={}", item.getId(), item.getFailureMessage());
                    }
                }
            }
            log.info("[es] 批量写入 {} 共 {} 条, 失败 {}", index.getIndexName(), docs.size(), failed);
            return docs.size() - failed;
        } catch (IOException e) {
            throw new IllegalStateException("批量写入 ES 失败: " + index.getIndexName(), e);
        }
    }

    /**
     * 构造带权重的字段表。
     * 为什么要有权重：用户搜"螺纹钢保证金"，希望"品种名=螺纹钢"的条款排前面，
     * 而不是某个恰好正文里提到"保证金"一词的无关记录。名称类字段给 2~3 倍权重。
     */
    private Map<String, Float> buildFieldBoosts(EsIndex index) {
        Map<String, Float> map = new LinkedHashMap<String, Float>();
        for (String field : index.getTextFields()) {
            map.put(field, weightOf(field));
        }
        return map;
    }

    /** 名称类字段提权，正文类字段保持 1.0 */
    private float weightOf(String field) {
        if (EsIndex.ALIAS_FIELD.equals(field)) {
            return 1.5f;
        }
        if ("varietyName".equals(field) || "contractName".equals(field)
                || "optionName".equals(field) || "supplierName".equals(field)) {
            return 3.0f;
        }
        if ("varietyCode".equals(field) || "contractCode".equals(field)
                || "optionCode".equals(field) || "exchangeName".equals(field)
                || "industryName".equals(field) || "operatorName".equals(field)) {
            return 2.0f;
        }
        return 1.0f;
    }

    /** 按 id 删除（文档不存在也算成功，ES delete 本身幂等） */
    public void deleteById(EsIndex index, Object id) {
        try {
            client.delete(new DeleteRequest(index.getIndexName(), String.valueOf(id)), RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new IllegalStateException("删除 ES 文档失败: " + index.getIndexName() + "#" + id, e);
        }
    }

    /** 按 id 查一条，补偿时用来判断文档是否存在 */
    public boolean existsById(EsIndex index, Object id) {
        try {
            return client.exists(new org.elasticsearch.action.get.GetRequest(index.getIndexName(), String.valueOf(id)),
                    RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.warn("[es] 判断文档存在性失败 {}/{}: {}", index.getIndexName(), id, e.getMessage());
            return false;
        }
    }

    /**
     * 关键字搜索。
     * 策略：multi_match(best_fields, operator=AND) 保证词都命中，避免结果发散；
     * 另加一条 PHRASE 查询提权 3 倍，让完整短语命中排前面（"螺纹钢 保证金" 比只命中"保证金"更相关）。
     */
    public SearchResult search(EsIndex index, String keyword, int page, int size) {
        Map<String, Float> fieldsWithBoost = buildFieldBoosts(index);
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            // 主查询用 cross_fields：把查询词拆分后允许"跨字段"满足 operator=AND。
            // 为什么不用 best_fields：best_fields 要求同一个字段同时命中所有词，
            // 搜"螺纹钢 保证金"时"螺纹钢"在 varietyName、"保证金"在 __alias，
            // 没有单个字段同时含两词，会返回 0 条（实测踩过）。
            // cross_fields 按词拆分后各字段取并集，才是用户期望的"多词多字段"语义。
            bool.should(QueryBuilders.multiMatchQuery(kw)
                    .fields(fieldsWithBoost)
                    .operator(Operator.AND)
                    .type(MultiMatchQueryBuilder.Type.CROSS_FIELDS));
            // 完整短语命中给更高权重，让"螺纹钢保证金"这类精确问法排前面
            bool.should(QueryBuilders.multiMatchQuery(kw)
                    .fields(fieldsWithBoost)
                    .type(MultiMatchQueryBuilder.Type.PHRASE)
                    .boost(3.0f));
            bool.minimumShouldMatch(1);
        } else {
            bool.must(QueryBuilders.matchAllQuery());
        }

        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .from(Math.max(0, (page - 1) * size))
                .size(size)
                .trackTotalHits(true)
                .sort(SortBuilders.scoreSort().order(SortOrder.DESC));

        SearchRequest request = new SearchRequest(index.getIndexName()).source(source);
        try {
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);
            List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>(hit.getSourceAsMap());
                // 别名是内部检索用的元数据，不返回给调用方，免得干扰前端渲染
                row.remove(EsIndex.ALIAS_FIELD);
                row.put("_id", hit.getId());
                row.put("_score", hit.getScore());
                records.add(row);
            }
            SearchResult result = new SearchResult();
            result.records = records;
            result.total = response.getHits().getTotalHits() == null
                    ? records.size() : response.getHits().getTotalHits().value;
            result.costMs = response.getTook().getMillis();
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("ES 搜索失败: " + index.getIndexName(), e);
        }
    }

    /**
     * 强制刷新索引，让刚写入的文档立即可被搜索/统计到。
     *
     * 为什么需要：ES 默认每 1 秒才 refresh 一次（近实时），bulk 写入后立刻 count
     * 会读到 0，导致同步结果误报"不一致"。全量同步、双写验证等场景要显式 refresh。
     * 注意别在线上高频写路径调用，refresh 有性能开销。
     */
    public void refresh(EsIndex index) {
        try {
            client.indices().refresh(
                    new org.elasticsearch.action.admin.indices.refresh.RefreshRequest(index.getIndexName()),
                    RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.warn("[es] refresh {} 失败: {}", index.getIndexName(), e.getMessage());
        }
    }

    /** 统计索引文档数 */
    public long count(EsIndex index) {
        try {
            SearchSourceBuilder source = new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(0);
            SearchResponse response = client.search(
                    new SearchRequest(index.getIndexName()).source(source), RequestOptions.DEFAULT);
            return response.getHits().getTotalHits() == null ? 0 : response.getHits().getTotalHits().value;
        } catch (IOException e) {
            throw new IllegalStateException("统计 ES 文档数失败: " + index.getIndexName(), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("对象转 JSON 失败: " + e.getMessage(), e);
        }
    }

    /** 搜索结果 */
    public static class SearchResult {
        public List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        public long total;
        public long costMs;
    }
}
