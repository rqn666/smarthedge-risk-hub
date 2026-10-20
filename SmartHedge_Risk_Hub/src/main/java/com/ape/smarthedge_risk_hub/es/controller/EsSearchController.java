package com.ape.smarthedge_risk_hub.es.controller;

import com.ape.smarthedge_risk_hub.common.Result;
import com.ape.smarthedge_risk_hub.es.config.EsProperties;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.core.EsDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 搜索接口。
 *
 * 降级设计：ES 不可用时返回 success=false 并给出提示，
 * 前端/调用方可以据此回退到原有数据库分页接口，而不是直接报 500。
 */
@RestController
@RequestMapping("/api/es/search")
public class EsSearchController {

    private static final Logger log = LoggerFactory.getLogger(EsSearchController.class);

    @Autowired
    private EsDocumentService documentService;

    @Autowired
    private EsProperties props;

    /**
     * 单索引搜索。
     * GET /api/es/search?index=futures_contract_text&keyword=螺纹钢&page=1&size=10
     */
    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam String index,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        EsIndex esIndex = resolveIndex(index);
        if (esIndex == null) {
            return Result.fail(400, "未知索引: " + index + "，可用值: " + indexNames());
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? props.getDefaultSize() : size;

        try {
            EsDocumentService.SearchResult sr = documentService.search(esIndex, keyword, p, s);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("index", esIndex.getIndexName());
            data.put("label", esIndex.getLabel());
            data.put("keyword", keyword);
            data.put("page", p);
            data.put("size", s);
            data.put("total", sr.total);
            data.put("costMs", sr.costMs);
            data.put("records", sr.records);
            return Result.success(data);
        } catch (Exception e) {
            log.error("[es] 搜索失败 index={} keyword={}: {}", index, keyword, e.getMessage());
            return Result.fail(503, "ES 搜索不可用，请降级走数据库查询: " + e.getMessage());
        }
    }

    /**
     * 多索引聚合搜索：一次查多个索引，各自返回结果。
     * GET /api/es/search/multi?keyword=螺纹钢
     */
    @GetMapping("/multi")
    public Result<Map<String, Object>> searchMulti(@RequestParam String keyword,
                                                   @RequestParam(required = false) List<String> indexes,
                                                   @RequestParam(required = false) Integer size) {
        int s = size == null || size < 1 ? props.getDefaultSize() : size;
        List<EsIndex> targets = new ArrayList<EsIndex>();
        if (indexes == null || indexes.isEmpty()) {
            targets.addAll(EsIndex.all());
        } else {
            for (String name : indexes) {
                EsIndex idx = resolveIndex(name);
                if (idx != null) {
                    targets.add(idx);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        long totalStart = System.currentTimeMillis();
        Map<String, Object> groups = new LinkedHashMap<String, Object>();
        long grandTotal = 0;
        for (EsIndex idx : targets) {
            try {
                EsDocumentService.SearchResult sr = documentService.search(idx, keyword, 1, s);
                Map<String, Object> g = new LinkedHashMap<String, Object>();
                g.put("label", idx.getLabel());
                g.put("total", sr.total);
                g.put("costMs", sr.costMs);
                g.put("records", sr.records);
                groups.put(idx.getIndexName(), g);
                grandTotal += sr.total;
            } catch (Exception e) {
                Map<String, Object> g = new LinkedHashMap<String, Object>();
                g.put("error", e.getMessage());
                groups.put(idx.getIndexName(), g);
            }
        }
        data.put("keyword", keyword);
        data.put("total", grandTotal);
        data.put("costMs", System.currentTimeMillis() - totalStart);
        data.put("groups", groups);
        return Result.success(data);
    }

    /** 列出所有可用索引，给前端做搜索范围下拉 */
    @GetMapping("/indexes")
    public Result<List<Map<String, Object>>> indexes() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (EsIndex index : EsIndex.all()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", index.getIndexName());
            item.put("table", index.getTableName());
            item.put("label", index.getLabel());
            item.put("searchableFields", index.getTextFields());
            list.add(item);
        }
        return Result.success(list);
    }

    private List<String> indexNames() {
        List<String> names = new ArrayList<String>();
        for (EsIndex index : EsIndex.all()) {
            names.add(index.getIndexName());
        }
        return names;
    }

    /**
     * 解析索引名，容错处理：
     * - 索引名（futures_contract_text）直接命中
     * - 表名（futures_contract_text）也命中（本项目两者恰好同名，但保留这层以防将来不一致）
     * - 中文名（期货合约条款）也命中，方便浏览器手测
     * - 忽略大小写与首尾空格
     */
    private EsIndex resolveIndex(String input) {
        if (input == null) {
            return null;
        }
        String key = input.trim();
        EsIndex index = EsIndex.ofIndexName(key);
        if (index != null) {
            return index;
        }
        index = EsIndex.ofTable(key);
        if (index != null) {
            return index;
        }
        for (EsIndex candidate : EsIndex.all()) {
            if (candidate.getLabel().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
    }
}
