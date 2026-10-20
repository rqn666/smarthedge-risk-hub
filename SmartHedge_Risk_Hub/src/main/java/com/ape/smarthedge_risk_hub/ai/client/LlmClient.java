package com.ape.smarthedge_risk_hub.ai.client;

import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型客户端。对外暴露同一套接口，内部按 ai.llm.tool-mode 走两种实现：
 * - prompt：deepseek-r1 不支持原生 tools，改为提示词约束输出 JSON，后端解析后执行
 * - native：qwen2.5 / 云端模型走 OpenAI tools 协议
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final AiProperties props;
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 多构造器时必须显式标注，否则 Spring 会退回无参构造并报 No default constructor found
     */
    @Autowired
    public LlmClient(AiProperties props) {
        this(props, buildRestTemplate(props));
    }

    public LlmClient(AiProperties props, RestTemplate rest) {
        this.props = props;
        this.rest = rest;
    }

    private static RestTemplate buildRestTemplate(AiProperties props) {
        RestTemplate template = new RestTemplate();
        // 默认 StringHttpMessageConverter 是 ISO-8859-1，中文会乱码，这里强制 UTF-8
        for (int i = 0; i < template.getMessageConverters().size(); i++) {
            if (template.getMessageConverters().get(i) instanceof StringHttpMessageConverter) {
                template.getMessageConverters().set(i, new StringHttpMessageConverter(StandardCharsets.UTF_8));
                break;
            }
        }
        return template;
    }

    /**
     * 发起一次对话。
     *
     * @param messages 完整对话（含 system）
     * @param tools    可用工具；prompt 模式下会被拼进 system 提示词
     */
    public LlmReply chat(List<ChatMessage> messages, List<ToolDef> tools) {
        List<ToolDef> safeTools = tools == null ? Collections.<ToolDef>emptyList() : tools;
        boolean promptMode = !"native".equalsIgnoreCase(props.getLlm().getToolMode());
        return promptMode ? chatByPrompt(messages, safeTools) : chatByNativeProtocol(messages, safeTools);
    }

    public LlmReply chat(List<ChatMessage> messages) {
        return chat(messages, Collections.<ToolDef>emptyList());
    }

    /** prompt 模式：把工具清单写进 system，让模型输出 JSON，后端解析 */
    private LlmReply chatByPrompt(List<ChatMessage> messages, List<ToolDef> tools) {
        List<ChatMessage> withTools = appendToolPrompt(messages, tools);
        String raw = callChat(withTools, false);
        return parsePromptReply(raw);
    }

    /** native 模式：走 OpenAI tools 协议 */
    private LlmReply chatByNativeProtocol(List<ChatMessage> messages, List<ToolDef> tools) {
        String raw = callChat(messages, tools);
        JsonNode messageNode = extractMessageNode(raw);
        if (messageNode == null) {
            return LlmReply.text("");
        }
        JsonNode toolCalls = messageNode.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0) {
            JsonNode fn = toolCalls.get(0).path("function");
            String name = fn.path("name").asText(null);
            String argsJson = fn.path("arguments").asText("{}");
            Map<String, String> args = parseArgs(argsJson);
            return LlmReply.toolCall(name, args, messageNode.path("content").asText(""));
        }
        String content = messageNode.path("content").asText("");
        LlmReply leaked = parseLeakedToolCall(content, tools);
        if (leaked != null) {
            return leaked;
        }
        return LlmReply.text(content);
    }

    /**
     * 兜底：本地小模型（qwen2.5 7B）偶发不走 tool_calls 字段，
     * 而是把工具调用当文本写在 content 里，形如 {"name":"queryFuturesContract","arguments":{"varietyName":"螺纹钢"}}。
     * 不识别的话这段原始 JSON 会直接当成答案返回给用户——实测踩到过。
     *
     * <p>防误判：只有当 name 确实命中本次下发的工具名时才认，避免把正常的 JSON 型回答当成工具调用。
     */
    private LlmReply parseLeakedToolCall(String content, List<ToolDef> tools) {
        if (content == null || content.indexOf('{') < 0) {
            return null;
        }
        JsonNode node = extractJson(stripThink(content));
        if (node == null || !node.has("name")) {
            return null;
        }
        String name = node.path("name").asText(null);
        if (!isKnownTool(name, tools)) {
            return null;
        }
        Map<String, String> args = new LinkedHashMap<String, String>();
        JsonNode argsNode = node.get("arguments");
        if (argsNode != null && argsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = argsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                args.put(e.getKey(), e.getValue().asText());
            }
        }
        log.warn("[ai] 模型把工具调用输出成了文本，已兜底解析为 tool_call: {}({})", name, args);
        return LlmReply.toolCall(name, args, "");
    }

    private boolean isKnownTool(String name, List<ToolDef> tools) {
        if (name == null || tools == null) {
            return false;
        }
        for (ToolDef tool : tools) {
            if (name.equals(tool.getName())) {
                return true;
            }
        }
        return false;
    }

    private String callChat(List<ChatMessage> messages, List<ToolDef> tools) {
        return callChat(messages, tools != null && !tools.isEmpty() ? tools : null, false, tools != null && !tools.isEmpty());
    }

    private String callChat(List<ChatMessage> messages, boolean dummy) {
        return callChat(messages, null, true, false);
    }

    private String callChat(List<ChatMessage> messages, List<ToolDef> tools, boolean dummyUnused, boolean withTools) {
        String url = props.getLlm().getBaseUrl() + "/v1/chat/completions";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getLlm().getModel());
        body.put("stream", false);
        body.put("temperature", props.getLlm().getTemperature());

        ArrayNode arr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = arr.addObject();
            n.put("role", m.getRole());
            n.put("content", m.getContent() == null ? "" : m.getContent());
        }
        if (withTools && tools != null) {
            ArrayNode toolArr = body.putArray("tools");
            for (ToolDef t : tools) {
                ObjectNode tool = toolArr.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.getName());
                fn.put("description", t.getDescription());
                ObjectNode params = fn.putObject("parameters");
                params.put("type", "object");
                ObjectNode properties = params.putObject("properties");
                for (Map.Entry<String, String> e : t.getParams().entrySet()) {
                    ObjectNode p = properties.putObject(e.getKey());
                    p.put("type", "string");
                    p.put("description", e.getValue());
                }
                ArrayNode required = params.putArray("required");
                for (String r : t.getRequired()) {
                    required.add(r);
                }
            }
            body.put("tool_choice", "auto");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        HttpEntity<String> entity = new HttpEntity<String>(body.toString(), headers);
        long start = System.currentTimeMillis();
        ResponseEntity<String> resp = rest.postForEntity(url, entity, String.class);
        log.info("[ai] llm called, mode={}, cost={}ms", props.getLlm().getToolMode(), System.currentTimeMillis() - start);
        return resp.getBody();
    }

    /**
     * 流式对话。**刻意不带 tools** —— 只用于「工具都执行完了，让模型组织最终答案」这一阶段。
     *
     * <p>为什么流式阶段必须摘掉工具：OpenAI tools 协议下必须先拿到完整的 tool_calls 才能执行，
     * 无法"边流边调"；而且此时若还带着工具，模型可能再发起一轮调用，答案永远出不来。
     * 所以整条链路是「工具阶段非流式 + 生成阶段流式」。
     *
     * <p>实现要点：走 Ollama 的 OpenAI 兼容 SSE（{@code stream=true}），
     * 用 {@code RestTemplate.execute} 直接读响应流。<b>不能用 postForEntity</b> ——
     * 后者会把整个响应缓冲完再返回，逐块读取的流式效果就没了。
     *
     * @param onDelta 每收到一个增量片段回调一次；传 null 表示只收集不回调
     * @return 拼接后的完整答案
     */
    public String chatStream(List<ChatMessage> messages, DeltaConsumer onDelta) {
        String url = props.getLlm().getBaseUrl() + "/v1/chat/completions";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getLlm().getModel());
        body.put("stream", true);
        body.put("temperature", props.getLlm().getTemperature());

        ArrayNode arr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = arr.addObject();
            n.put("role", m.getRole());
            n.put("content", m.getContent() == null ? "" : m.getContent());
        }
        final String payload = body.toString();
        final StringBuilder full = new StringBuilder();
        long start = System.currentTimeMillis();

        rest.execute(url, HttpMethod.POST, request -> {
            request.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            if (request.getBody() != null) {
                request.getBody().write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }, response -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String piece = extractDelta(data);
                    if (piece != null && !piece.isEmpty()) {
                        full.append(piece);
                        if (onDelta != null) {
                            onDelta.accept(piece);
                        }
                    }
                }
            }
            return null;
        });

        log.info("[ai] llm stream finished, chars={}, cost={}ms",
                full.length(), System.currentTimeMillis() - start);
        return full.toString();
    }

    /** 从 SSE 的 data 块里取出 choices[0].delta.content；非内容块（心跳、结束块）返回 null */
    private String extractDelta(String data) {
        try {
            JsonNode delta = mapper.readTree(data).path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() || delta.isNull() ? null : delta.asText("");
        } catch (Exception e) {
            return null;
        }
    }

    /** 流式增量回调。用接口而不是 Consumer，便于后续加 token 计数等扩展 */
    public interface DeltaConsumer {
        void accept(String delta);
    }

    private JsonNode extractMessageNode(String raw) {
        try {
            JsonNode root = mapper.readTree(raw == null ? "" : raw);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message");
            }
        } catch (Exception e) {
            log.warn("[ai] 解析大模型响应失败: {}", raw);
        }
        return null;
    }

    /** 解析 prompt 模式输出：剥离思考块与代码围栏，取第一个合法 JSON */
    public LlmReply parsePromptReply(String raw) {
        JsonNode messageNode = extractMessageNode(raw);
        String content = messageNode == null ? (raw == null ? "" : raw) : messageNode.path("content").asText("");
        String cleaned = stripThink(content);

        JsonNode node = extractJson(cleaned);
        if (node != null) {
            String tool = node.path("tool").asText(null);
            if (tool != null && !"none".equalsIgnoreCase(tool)) {
                if (node.has("result")) {
                    log.warn("[ai] 模型自造了 result 字段，已丢弃（防幻觉）: {}", node.get("result"));
                }
                Map<String, String> args = new LinkedHashMap<String, String>();
                JsonNode argsNode = node.get("args");
                if (argsNode != null && argsNode.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = argsNode.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        args.put(e.getKey(), e.getValue().asText());
                    }
                }
                return LlmReply.toolCall(tool, args, node.path("answer").asText(""));
            }
            String answer = node.path("answer").asText("");
            if (!answer.isEmpty()) {
                return LlmReply.text(answer);
            }
        }
        return LlmReply.text(cleaned.trim());
    }

    private String stripThink(String text) {
        if (text == null) {
            return "";
        }
        String s = THINK_PATTERN.matcher(text).replaceAll("");
        Matcher m = FENCE_PATTERN.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return s;
    }

    private JsonNode extractJson(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return null;
        }
        JsonNode direct = tryParse(s);
        if (direct != null) {
            return direct;
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return tryParse(s.substring(start, end + 1));
        }
        return null;
    }

    private JsonNode tryParse(String s) {
        try {
            JsonNode node = mapper.readTree(s);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> parseArgs(String argsJson) {
        Map<String, String> args = new LinkedHashMap<String, String>();
        try {
            JsonNode node = mapper.readTree(argsJson);
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                args.put(e.getKey(), e.getValue().asText());
            }
        } catch (Exception e) {
            log.warn("[ai] 工具参数解析失败: {}", argsJson);
        }
        return args;
    }

    /** 把工具说明追加到第一条 system 消息后面 */
    private List<ChatMessage> appendToolPrompt(List<ChatMessage> messages, List<ToolDef> tools) {
        List<ChatMessage> copy = new ArrayList<ChatMessage>(messages);
        if (tools == null || tools.isEmpty()) {
            return copy;
        }
        StringBuilder sb = new StringBuilder("\n\n# 可用工具\n");
        for (ToolDef t : tools) {
            sb.append("- ").append(t.getName()).append("(");
            sb.append(joinKeys(t.getParams())).append(")");
            sb.append("：").append(t.getDescription()).append("\n");
        }
        sb.append("\n需要调用工具时，只输出一行 JSON：{\"tool\":\"工具名\",\"args\":{...}}\n");
        sb.append("不需要调用工具时，输出：{\"tool\":\"none\",\"answer\":\"你的回答\"}\n");
        sb.append("严禁编造工具返回结果，结果会由系统回灌。\n");

        int sysIndex = -1;
        for (int i = 0; i < copy.size(); i++) {
            if ("system".equals(copy.get(i).getRole())) {
                sysIndex = i;
                break;
            }
        }
        if (sysIndex >= 0) {
            ChatMessage old = copy.get(sysIndex);
            copy.set(sysIndex, new ChatMessage("system", old.getContent() + sb.toString()));
        } else {
            copy.add(0, new ChatMessage("system", sb.toString()));
        }
        return copy;
    }

    private String joinKeys(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (String k : params.keySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k);
        }
        return sb.toString();
    }

    // ======================= 内部数据结构 =======================

    public static class ChatMessage {
        private String role;
        private String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    public static class ToolDef {
        private String name;
        private String description;
        private Map<String, String> params = new LinkedHashMap<String, String>();
        private List<String> required = new ArrayList<String>();

        public ToolDef() {
        }

        public ToolDef(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public ToolDef param(String name, String desc) {
            this.params.put(name, desc);
            return this;
        }

        public ToolDef required(String name) {
            this.required.add(name);
            return this;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params;
        }

        public List<String> getRequired() {
            return required;
        }

        public void setRequired(List<String> required) {
            this.required = required;
        }
    }

    public static class LlmReply {
        private final String content;
        private final String toolName;
        private final Map<String, String> toolArgs;

        private LlmReply(String content, String toolName, Map<String, String> toolArgs) {
            this.content = content;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
        }

        public static LlmReply text(String content) {
            return new LlmReply(content, null, null);
        }

        public static LlmReply toolCall(String toolName, Map<String, String> args, String content) {
            return new LlmReply(content, toolName, args);
        }

        public boolean hasToolCall() {
            return toolName != null && !toolName.isEmpty();
        }

        public String getContent() {
            return content;
        }

        public String getToolName() {
            return toolName;
        }

        public Map<String, String> getToolArgs() {
            return toolArgs;
        }
    }
}
