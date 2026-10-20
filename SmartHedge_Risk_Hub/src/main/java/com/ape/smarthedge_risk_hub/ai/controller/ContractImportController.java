package com.ape.smarthedge_risk_hub.ai.controller;

import com.ape.smarthedge_risk_hub.ai.chat.ChatService;
import com.ape.smarthedge_risk_hub.ai.tool.impl.ContractImportTools;
import com.ape.smarthedge_risk_hub.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合约条款「文本导入」入口。这是本项目唯一由 AI 触发写库的能力，因此拆成两个入口：
 *
 * <ul>
 *   <li>{@code /import} —— 把文本交给大模型，由它自动抽取字段并调用 @Tool。
 *       用于演示 Function Calling 的写操作，也方便对话式录入。</li>
 *   <li>{@code /save} —— 用户在前端确认预览后，凭 previewToken 直接落库。
 *       不再走一次大模型：既省掉几十秒推理，又保证「确认什么就存什么」。</li>
 * </ul>
 *
 * <p>两个入口共用同一套服务端令牌，所以<b>无论走哪条路，都无法绕过 preview 直接写库</b>。
 */
@RestController
@RequestMapping("/api/ai/contract")
public class ContractImportController {

    /** 从工具返回的 JSON 文本里抠出 previewToken，省得前端自己解析 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"previewToken\"\\s*:\\s*\"([0-9a-zA-Z]+)\"");

    @Autowired
    private ChatService chatService;

    @Autowired
    private ContractImportTools contractImportTools;

    /**
     * 用大模型解析合约条款文本并（可选）入库。
     *
     * <p>body: {@code {"text":"合约条款原文","dryRun":true}}
     * <p>dryRun 不传或为 true → 只预览，不写库（默认值刻意设为"安全"的那一侧）。
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importText(@RequestBody Map<String, Object> body) {
        String text = body == null ? null : asString(body.get("text"));
        if (text == null || text.trim().isEmpty()) {
            return Result.fail(400, "请提供合约条款文本：body = {\"text\":\"合约条款原文\",\"dryRun\":true}");
        }
        boolean dryRun = body.get("dryRun") == null
                || Boolean.parseBoolean(String.valueOf(body.get("dryRun")));

        String message = "请解析下面这段期货合约条款文本，抽取字段后调用 previewFuturesContractText 生成预览：\n\n"
                + text.trim()
                + (dryRun
                ? "\n\n（本次只需要预览结果，不要调用 saveFuturesContractText 保存。）"
                : "\n\n（用户已确认字段无误：预览校验通过后，请立即调用 saveFuturesContractText 并传入 previewToken 完成保存。）");

        ChatService.ChatResult r = chatService.chatWithTrace(message);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("dryRun", dryRun);
        data.put("answer", r.answer);
        data.put("rounds", r.rounds);
        data.put("costMs", r.costMs);
        List<Map<String, Object>> trace = new ArrayList<Map<String, Object>>();
        for (ChatService.ToolTrace t : r.trace) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("round", t.round);
            item.put("tool", t.name);
            item.put("args", t.args);
            item.put("result", t.result);
            item.put("costMs", t.costMs);
            trace.add(item);
        }
        data.put("toolTrace", trace);
        data.put("previewToken", extractToken(trace));
        return Result.success(data);
    }

    /**
     * 用户确认预览后直接落库（不再走大模型）。
     *
     * <p>body: {@code {"previewToken":"上一步返回的令牌","contractCode":"RB2410可选"}}
     */
    @PostMapping("/save")
    public Result<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("previewToken");
        if (token == null || token.trim().isEmpty()) {
            return Result.fail(400, "请提供 previewToken：body = {\"previewToken\":\"xxx\"}");
        }
        Map<String, Object> r = contractImportTools.save(token, body.get("contractCode"));
        if (Boolean.TRUE.equals(r.get("success"))) {
            return Result.success(r);
        }
        return Result.fail(400, String.valueOf(r.get("message")));
    }

    /** 从工具调用轨迹里提取 previewToken；没有则返回 null */
    private String extractToken(List<Map<String, Object>> trace) {
        for (Map<String, Object> item : trace) {
            Object result = item.get("result");
            if (result == null) {
                continue;
            }
            Matcher m = TOKEN_PATTERN.matcher(String.valueOf(result));
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
