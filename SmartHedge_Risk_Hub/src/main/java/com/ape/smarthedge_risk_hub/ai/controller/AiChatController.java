package com.ape.smarthedge_risk_hub.ai.controller;

import com.ape.smarthedge_risk_hub.ai.chat.ChatService;
import com.ape.smarthedge_risk_hub.ai.chat.StreamListener;
import com.ape.smarthedge_risk_hub.ai.chat.SystemPromptLoader;
import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.ape.smarthedge_risk_hub.ai.tool.ToolExecutor;
import com.ape.smarthedge_risk_hub.ai.tool.ToolRegistry;
import com.ape.smarthedge_risk_hub.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 问答入口。M2：Function Calling 闭环（自动识别提示词 → 调工具 → 回灌 → 作答）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private SystemPromptLoader promptLoader;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiProperties props;

    /**
     * 问答（带工具调用轨迹）
     * body: { "message": "螺纹钢期货保证金比例是多少" }
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        return Result.success(toData(chatService.chatWithTrace(message)));
    }

    /**
     * 浏览器直接访问测试用：GET /api/ai/ask?message=螺纹钢保证金比例
     * （/chat 只接受 POST，浏览器地址栏发的是 GET，会 404）
     */
    @GetMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestParam(value = "message", defaultValue = "") String message) {
        return Result.success(toData(chatService.chatWithTrace(message)));
    }

    /**
     * 流式问答（SSE）。浏览器/前端 EventSource 直接接：
     * GET /api/ai/chat/stream?message=螺纹钢保证金比例
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code tool_start} — 模型决定调工具，data: {round,tool,args}</li>
     *   <li>{@code tool_end}   — 工具执行完，data: {round,tool,costMs,result}</li>
     *   <li>{@code delta}      — 答案增量，data: {text}</li>
     *   <li>{@code done}       — 结束，data: {answer,rounds,costMs}</li>
     *   <li>{@code error}      — 异常，data: {message}</li>
     * </ul>
     *
     * <p>超时给到 readTimeout 的两倍：一次问答含多轮大模型推理，
     * 本地 7B 模型单轮就可能几十秒，超时设短了会在出答案前被容器掐断。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(value = "message", defaultValue = "") String message) {
        long timeout = props.getLlm().getReadTimeoutSeconds() * 1000L * 2;
        SseEmitter emitter = new SseEmitter(timeout);
        SseStreamListener listener = new SseStreamListener(emitter);
        emitter.onTimeout(listener::completeQuietly);
        emitter.onError(e -> listener.completeQuietly());
        // 交给独立线程池执行，尽快把 emitter 返回给容器（否则要一直占着 Tomcat 线程）
        chatService.chatStreamAsync(message, listener);
        return emitter;
    }

    /** 查看已注册的工具清单，验证 @Tool 扫描是否生效 */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ToolRegistry.ToolMeta meta : toolRegistry.list()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", meta.getName());
            item.put("description", meta.getDescription());
            item.put("params", meta.getParams());
            item.put("required", meta.getRequired());
            list.add(item);
        }
        return Result.success(list);
    }

    /** 接口自检：列出可用入口，避免访问未知路径时落到 Whitelabel 404 */
    @GetMapping("/help")
    public Result<Map<String, Object>> help() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("POST /api/ai/chat", "body: {\"message\":\"你的问题\"}，返回答案+工具调用轨迹");
        data.put("GET  /api/ai/ask", "?message=你的问题（浏览器直接测，中文需 URL 编码）");
        data.put("GET  /api/ai/chat/stream", "?message=你的问题，SSE 流式；事件：tool_start/tool_end/delta/done/error");
        data.put("POST /api/ai/contract/import", "body: {\"text\":\"合约条款原文\",\"dryRun\":true}，AI 解析文本并入库（dryRun 仅预览）");
        data.put("GET  /api/ai/tools", "查看已注册的工具清单");
        data.put("GET  /api/ai/prompt/reload", "改完 system-prompt.txt 后热加载，无需重启");
        return Result.success(data);
    }

    /** 访问 /api/ai 或 /api/ai/ 时给清单，不落到 404 */
    @GetMapping({"", "/"})
    public Result<Map<String, Object>> index() {
        return help();
    }

    /** 改完 system-prompt.txt 后调用，无需重启 */
    @GetMapping("/prompt/reload")
    public Result<String> reloadPrompt() {
        return Result.success(promptLoader.reload());
    }

    private Map<String, Object> toData(ChatService.ChatResult result) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("answer", result.answer);
        data.put("rounds", result.rounds);
        data.put("costMs", result.costMs);
        data.put("hitRoundLimit", result.hitRoundLimit);
        List<Map<String, Object>> trace = new ArrayList<Map<String, Object>>();
        for (ChatService.ToolTrace t : result.trace) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("round", t.round);
            item.put("tool", t.name);
            item.put("args", t.args);
            item.put("result", t.result);
            item.put("costMs", t.costMs);
            trace.add(item);
        }
        data.put("toolTrace", trace);
        return data;
    }

    /**
     * 把 StreamListener 事件适配成 SSE。
     * 推送失败（客户端提前断开）一律静默忽略 —— 否则日志里会刷一堆无关异常，
     * 而且此时已经没有接收方，重试也没有意义。
     */
    private static class SseStreamListener implements StreamListener {

        private final SseEmitter emitter;
        private final ObjectMapper mapper = new ObjectMapper();

        SseStreamListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onToolStart(int round, String tool, Map<String, String> args) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("round", round);
            data.put("tool", tool);
            data.put("args", args);
            send("tool_start", data);
        }

        @Override
        public void onToolEnd(int round, String tool, String result, long costMs) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("round", round);
            data.put("tool", tool);
            data.put("costMs", costMs);
            data.put("result", result);
            send("tool_end", data);
        }

        @Override
        public void onDelta(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("text", text);
            send("delta", data);
        }

        @Override
        public void onDone(ChatService.ChatResult result) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("answer", result.answer);
            data.put("rounds", result.rounds);
            data.put("costMs", result.costMs);
            data.put("hitRoundLimit", result.hitRoundLimit);
            send("done", data);
            completeQuietly();
        }

        @Override
        public void onError(String message) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("message", message);
            send("error", data);
            completeQuietly();
        }

        private void send(String event, Map<String, Object> data) {
            try {
                // Jackson 会把换行转义成 \n，不会破坏 SSE 按行分帧的格式
                emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
            } catch (Exception e) {
                // 客户端已断开
            }
        }

        void completeQuietly() {
            try {
                emitter.complete();
            } catch (Exception e) {
                // 已经结束过了
            }
        }
    }
}
