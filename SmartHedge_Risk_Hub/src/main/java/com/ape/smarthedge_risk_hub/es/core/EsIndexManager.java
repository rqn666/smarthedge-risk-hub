package com.ape.smarthedge_risk_hub.es.core;

import com.ape.smarthedge_risk_hub.es.config.EsProperties;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 索引管理：创建/删除/存在性判断。
 * 建索引时 text 字段统一用 ik_max_word（召回全），搜索时用 ik_smart（精度高）。
 */
@Component
public class EsIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EsIndexManager.class);

    /** 索引级默认分词器：没有显式指定 analyzer 的 text 字段用它 */
    private static final String INDEX_ANALYZER = "ik_max_word";
    private static final String SEARCH_ANALYZER = "ik_smart";

    @Autowired
    private RestHighLevelClient client;

    @Autowired
    private EsProperties props;

    public boolean exists(EsIndex index) {
        try {
            return client.indices().exists(new GetIndexRequest(index.getIndexName()), RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.warn("[es] 判断索引 {} 是否存在失败: {}", index.getIndexName(), e.getMessage());
            return false;
        }
    }

    /** 创建索引（已存在则跳过） */
    public boolean createIfAbsent(EsIndex index) {
        if (exists(index)) {
            log.info("[es] 索引 {} 已存在，跳过创建", index.getIndexName());
            return false;
        }
        try {
            CreateIndexRequest request = new CreateIndexRequest(index.getIndexName());
            request.settings(Settings.builder()
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 0)
                    .put("analysis.analyzer.default.type", "custom")
                    .put("analysis.analyzer.default.tokenizer", INDEX_ANALYZER)
                    .build());
            request.mapping(buildMapping(index));
            client.indices().create(request, RequestOptions.DEFAULT);
            log.info("[es] 索引 {} 创建成功, 字段数={}", index.getIndexName(), index.getFields().size());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("创建 ES 索引失败: " + index.getIndexName() + ", " + e.getMessage(), e);
        }
    }

    /** 删除索引（重建用） */
    public boolean delete(EsIndex index) {
        if (!exists(index)) {
            return false;
        }
        try {
            AcknowledgedResponse resp = client.indices()
                    .delete(new DeleteIndexRequest(index.getIndexName()), RequestOptions.DEFAULT);
            log.info("[es] 索引 {} 已删除, acknowledged={}", index.getIndexName(), resp.isAcknowledged());
            return resp.isAcknowledged();
        } catch (IOException e) {
            throw new IllegalStateException("删除 ES 索引失败: " + index.getIndexName() + ", " + e.getMessage(), e);
        }
    }

    /** 确保所有索引都存在 */
    public void createAllIfAbsent() {
        for (EsIndex index : EsIndex.all()) {
            createIfAbsent(index);
        }
    }

    /**
     * 构建 mapping。规则：
     * - text  → 分词检索，建索引用 ik_max_word，搜索用 ik_smart
     * - keyword → 精确匹配/聚合，不分词
     * - long/integer/double → 数值
     * - date → 支持 yyyy-MM-dd HH:mm:ss 与 yyyy-MM-dd
     */
    private XContentBuilder buildMapping(EsIndex index) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("properties");
        for (Map.Entry<String, String> entry : index.getFields().entrySet()) {
            String field = entry.getKey();
            String type = entry.getValue();
            builder.startObject(field);
            if ("text".equals(type)) {
                appendTextMapping(builder);
            } else if ("date".equals(type)) {
                builder.field("type", "date");
                builder.field("format", "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis");
            } else {
                builder.field("type", type);
            }
            builder.endObject();
        }
        // 别名字段：没有别名就不建，避免留空字段
        if (!index.getAliases().isEmpty()) {
            builder.startObject(EsIndex.ALIAS_FIELD);
            appendTextMapping(builder);
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }

    /** text 字段的统一 mapping：ik_max_word 建索引 + ik_smart 搜索 + keyword 子字段 */
    private void appendTextMapping(XContentBuilder builder) throws IOException {
        builder.field("type", "text");
        builder.field("analyzer", INDEX_ANALYZER);
        builder.field("search_analyzer", SEARCH_ANALYZER);
        builder.startObject("fields");
        builder.startObject("keyword");
        builder.field("type", "keyword");
        builder.field("ignore_above", 256);
        builder.endObject();
        builder.endObject();
    }
}
