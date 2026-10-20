package com.ape.smarthedge_risk_hub.es.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ES 文档载体：本质是一个字段名 -> 值的 Map，外加业务主键。
 *
 * 为什么不直接把 POJO 丢给 ES？
 * 因为业务 POJO 里有 @TableField(exist=false) 的关联对象（tradingVariety、operator 等）
 * 和一堆 ES 里没建 mapping 的字段，直接序列化会被 ES 拒绝（dynamic strict 或产生垃圾字段）。
 * 走 Map 可以精确控制落到 ES 的字段，且不受 POJO 变更影响。
 */
public class EsDoc {

    /** 业务主键（同时也是 ES 的 _id），不进 JSON body */
    private final String id;

    /** 真正写进 ES 的字段 */
    private final Map<String, Object> fields = new LinkedHashMap<String, Object>();

    public EsDoc(Object id) {
        this.id = String.valueOf(id);
    }

    public static EsDoc of(Object id) {
        return new EsDoc(id);
    }

    /** 非空才放进文档，避免 null 值污染 date/long 字段导致 mapping 冲突 */
    public EsDoc put(String field, Object value) {
        if (value != null) {
            fields.put(field, value);
        }
        return this;
    }

    @JsonIgnore
    public String getId() {
        return id;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    @Override
    public String toString() {
        return "EsDoc{id=" + id + ", fields=" + fields.keySet() + "}";
    }
}
