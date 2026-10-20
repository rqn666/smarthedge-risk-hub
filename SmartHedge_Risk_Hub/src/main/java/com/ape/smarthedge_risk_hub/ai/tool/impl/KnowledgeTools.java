package com.ape.smarthedge_risk_hub.ai.tool.impl;

import com.ape.smarthedge_risk_hub.ai.rag.KnowledgeService;
import com.ape.smarthedge_risk_hub.ai.tool.Tool;
import com.ape.smarthedge_risk_hub.ai.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库检索工具：RAG 与 Function Calling 的接合点。
 *
 * 定位区别（写给大模型看，也写给维护者看）：
 * - 本工具查**非结构化知识**：制度、流程、规则、历史文档 —— 答案在文字里，靠语义相似度召回
 * - 其他工具查**结构化业务数据**：合约条款、现货、供应商 —— 答案在表里，靠精确条件查询
 * 两者不可互相替代：用 SQL 查「套保审批流程」查不到，用向量检索算「保证金=5%」也不可靠。
 */
@Component
public class KnowledgeTools {

    @Autowired
    private KnowledgeService knowledgeService;

    @Tool(name = "searchKnowledgeBase",
            description = "检索企业私人知识库，返回与问题最相关的原文片段及来源文档名。"
                    + "当用户询问公司制度、风控流程、管理办法、审批规范、内部规则、"
                    + "历史文档、业务指引等【非结构化知识】时调用本工具。"
                    + "如果知识库没有相关文档，工具会明确返回未检索到，此时应如实告知用户，"
                    + "严禁编造制度条款或文档名称。"
                    + "注意：查询实时业务数据（某合约的保证金比例、现货价格、供应商信息）不要用本工具。")
    public Map<String, Object> searchKnowledgeBase(
            @ToolParam("检索用的关键词或问题，尽量具体，例如「套期保值审批流程」「保证金收取标准」")
                    String query,
            @ToolParam(value = "返回的片段条数，默认 5，最多 10", required = false)
                    Integer topK) {
        Integer k = topK;
        if (k != null && (k < 1 || k > 10)) {
            k = 5;
        }
        return knowledgeService.searchAsToolResult(query, k);
    }
}
