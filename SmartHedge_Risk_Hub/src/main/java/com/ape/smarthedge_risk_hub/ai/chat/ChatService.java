package com.ape.smarthedge_risk_hub.ai.chat;

import com.ape.smarthedge_risk_hub.ai.client.LlmClient;
import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.ape.smarthedge_risk_hub.ai.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对话编排。Function Calling 完整闭环：
 * 组装 messages(+工具) → 调模型 → 模型要工具则执行并回灌 → 再调模型 → 直到给出答案或达到轮次上限。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 「参考：《…》」标注的匹配式（含全角/半角冒号与包裹括号） */
    private static final Pattern CITATION_PATTERN =
            Pattern.compile("[（(]?\\s*参考[：:]\\s*《[^》]*》\\s*[）)]?");

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private SystemPromptLoader promptLoader;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private AiProperties props;

    /** 纯对话入口 */
    public String chat(String userMessage) {
        return chatWithTrace(userMessage).answer;
    }

    /** 带轨迹的对话，前端可展示「模型调了哪些工具」 */
    public ChatResult chatWithTrace(String userMessage) {
        ChatResult result = new ChatResult();
        if (userMessage == null || userMessage.trim().isEmpty()) {
            result.answer = "请输入您的问题。";
            return result;
        }

        List<LlmClient.ChatMessage> messages = new ArrayList<LlmClient.ChatMessage>();
        messages.add(LlmClient.ChatMessage.system(promptLoader.load()));
        messages.add(LlmClient.ChatMessage.user(userMessage.trim()));

        List<LlmClient.ToolDef> tools = toolExecutor.asToolDefs();
        int maxRounds = props.getLlm().getMaxToolRounds();
        long start = System.currentTimeMillis();

        for (int round = 1; round <= maxRounds; round++) {
            LlmClient.LlmReply reply = llmClient.chat(messages, tools);

            if (!reply.hasToolCall()) {
                result.answer = sanitizeCitation(normalize(reply.getContent()), result.trace);
                result.rounds = round;
                result.costMs = System.currentTimeMillis() - start;
                log.info("[ai] 对话完成, rounds={}, toolCalls={}, cost={}ms",
                        round, result.trace.size(), result.costMs);
                return result;
            }

            // 模型要调工具：先把它的输出记进上下文，再执行、回灌
            String toolName = reply.getToolName();
            Map<String, String> args = reply.getToolArgs() == null
                    ? new LinkedHashMap<String, String>() : reply.getToolArgs();
            messages.add(LlmClient.ChatMessage.assistant(reply.getContent()));

            long toolStart = System.currentTimeMillis();
            String observation = toolExecutor.execute(toolName, args);
            long toolCost = System.currentTimeMillis() - toolStart;

            ToolTrace trace = new ToolTrace();
            trace.round = round;
            trace.name = toolName;
            trace.args = args;
            trace.result = observation;
            trace.costMs = toolCost;
            result.trace.add(trace);

            messages.add(LlmClient.ChatMessage.user("OBSERVATION: " + observation
                    + "\n请基于以上工具返回的数据回答用户的问题，不要编造数据。"));
        }

        // 达到轮次上限：不再提供工具，强制它给结论
        log.warn("[ai] 达到工具调用上限 {} 轮，强制要求模型给结论", maxRounds);
        messages.add(LlmClient.ChatMessage.user("已达到工具调用次数上限，请立即基于现有信息给出最终回答。"));
        LlmClient.LlmReply finalReply = llmClient.chat(messages);
        result.answer = sanitizeCitation(normalize(finalReply.getContent()), result.trace);
        result.rounds = maxRounds;
        result.costMs = System.currentTimeMillis() - start;
        result.hitRoundLimit = true;
        return result;
    }

    /**
     * 流式对话。整条链路分两个阶段，这是本实现最关键的取舍：
     *
     * <p><b>阶段 1｜工具阶段（非流式）</b>：带 tools 调用，最多 maxToolRounds-1 轮。
     * 必须非流式 —— OpenAI tools 协议要先拿到完整 tool_calls 才能执行，"边流边调"做不到。
     * 若某一轮模型不再要工具（比如简单问题），说明答案已经生成，直接分片推送，省一次调用。
     *
     * <p><b>阶段 2｜生成阶段（流式）</b>：工具结果回灌后，<b>摘掉工具定义</b>，用 stream=true
     * 逐 token 输出最终答案 —— 既让首字尽快到达，又避免模型再发起一轮工具调用。
     *
     * <p>用 {@code @Async} 放到独立线程池：一次对话可能跑几十秒，
     * 占着 Tomcat 工作线程会拖垮 Web 容器。
     */
    @Async("aiExecutor")
    public void chatStreamAsync(String userMessage, StreamListener listener) {
        ChatResult result = new ChatResult();
        long start = System.currentTimeMillis();
        try {
            if (userMessage == null || userMessage.trim().isEmpty()) {
                result.answer = "请输入您的问题。";
                listener.onDelta(result.answer);
                listener.onDone(result);
                return;
            }

            List<LlmClient.ChatMessage> messages = new ArrayList<LlmClient.ChatMessage>();
            messages.add(LlmClient.ChatMessage.system(promptLoader.load()));
            messages.add(LlmClient.ChatMessage.user(userMessage.trim()));

            List<LlmClient.ToolDef> tools = toolExecutor.asToolDefs();
            // 工具轮次用独立配置，默认 1 轮：拿到工具结果后立刻进流式生成答案。
            // 这里不能沿用 maxToolRounds 去减一 —— 那样第 2 轮还会走一次非流式判断
            // （实测多等约 50s，用户全程看不到字），流式就失去意义了。
            int toolRounds = Math.max(1, props.getLlm().getStreamToolRounds());
            boolean usedTool = false;

            for (int round = 1; round <= toolRounds; round++) {
                LlmClient.LlmReply reply = llmClient.chat(messages, tools);

                if (!reply.hasToolCall()) {
                    // 模型不需要工具 → 答案已在手，切成小块推送
                    result.answer = sanitizeCitation(normalize(reply.getContent()), result.trace);
                    result.rounds = round;
                    result.costMs = System.currentTimeMillis() - start;
                    pushInChunks(listener, result.answer);
                    log.info("[ai] 流式对话结束(无需工具), rounds={}, cost={}ms", round, result.costMs);
                    listener.onDone(result);
                    return;
                }

                String toolName = reply.getToolName();
                Map<String, String> args = reply.getToolArgs() == null
                        ? new LinkedHashMap<String, String>() : reply.getToolArgs();
                messages.add(LlmClient.ChatMessage.assistant(reply.getContent()));
                listener.onToolStart(round, toolName, args);

                long toolStart = System.currentTimeMillis();
                String observation = toolExecutor.execute(toolName, args);
                long toolCost = System.currentTimeMillis() - toolStart;

                ToolTrace trace = new ToolTrace();
                trace.round = round;
                trace.name = toolName;
                trace.args = args;
                trace.result = observation;
                trace.costMs = toolCost;
                result.trace.add(trace);
                listener.onToolEnd(round, toolName, observation, toolCost);

                messages.add(LlmClient.ChatMessage.user("OBSERVATION: " + observation
                        + "\n请基于以上工具返回的数据回答用户的问题，不要编造数据。"));
                usedTool = true;
            }

            // 阶段 2：摘掉工具，流式生成最终答案
            if (usedTool) {
                messages.add(LlmClient.ChatMessage.user("请基于以上工具返回的信息，给出简洁的最终回答。"));
            }
            final StringBuilder answer = new StringBuilder();
            // 末尾保留窗口：来源标注总是出现在答案结尾，先扣住最后 24 个字符不发，
            // 收尾时过滤掉伪造的来源标注再补推，避免流式路径漏出「参考：《不存在文档》」
            final StringBuilder pending = new StringBuilder();
            final int holdBack = 24;
            llmClient.chatStream(messages, new LlmClient.DeltaConsumer() {
                @Override
                public void accept(String delta) {
                    answer.append(delta);
                    pending.append(delta);
                    if (pending.length() > holdBack) {
                        int cut = pending.length() - holdBack;
                        listener.onDelta(pending.substring(0, cut));
                        pending.delete(0, cut);
                    }
                }
            });

            result.answer = sanitizeCitation(normalize(answer.toString()), result.trace);
            String tail = sanitizeCitation(pending.toString(), result.trace);
            if (tail != null && !tail.isEmpty()) {
                listener.onDelta(tail);
            }
            result.rounds = toolRounds + 1;
            result.costMs = System.currentTimeMillis() - start;
            log.info("[ai] 流式对话结束, rounds={}, toolCalls={}, cost={}ms",
                    result.rounds, result.trace.size(), result.costMs);
            listener.onDone(result);
        } catch (Exception e) {
            log.error("[ai] 流式对话失败: {}", e.getMessage(), e);
            listener.onError("对话失败：" + e.getMessage());
        }
    }

    /** 把已经拿到的完整答案切成小块推送，让前端渲染节奏接近流式（这条路径没有增量 token 可用） */
    private void pushInChunks(StreamListener listener, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int step = 24;
        for (int i = 0; i < text.length(); i += step) {
            listener.onDelta(text.substring(i, Math.min(text.length(), i + step)));
        }
    }

    private String normalize(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "未获取到有效回答，请稍后重试。";
        }
        return content.trim();
    }

    /**
     * 来源标注只允许出现在「本轮真的检索过知识库」的回答里。
     *
     * <p>实测 7B 模型在纯业务数据查询（如查螺纹钢交易所）后也会硬加一句
     * 「参考：《期货合约信息列表》」——该文档并不存在，属于凭空捏造引用。
     * 提示词层面已明确禁止，但小模型仍偶发违约，因此这里做服务端兜底：
     * 本轮若没调用 searchKnowledgeBase，就把这类标注直接删掉。
     */
    private String sanitizeCitation(String answer, List<ToolTrace> trace) {
        if (answer == null || answer.isEmpty() || answer.indexOf("参考") < 0) {
            return answer;
        }
        boolean usedKnowledgeBase = false;
        for (ToolTrace item : trace) {
            if ("searchKnowledgeBase".equals(item.name)) {
                usedKnowledgeBase = true;
                break;
            }
        }
        if (usedKnowledgeBase) {
            return answer;
        }
        String cleaned = CITATION_PATTERN.matcher(answer).replaceAll("").trim();
        if (!cleaned.equals(answer)) {
            log.warn("[ai] 本轮未检索知识库却输出了来源标注，已删除：{}", answer);
        }
        return cleaned;
    }

    /** 单次对话结果，含工具调用轨迹 */
    public static class ChatResult {
        public String answer;
        public List<ToolTrace> trace = new ArrayList<ToolTrace>();
        public int rounds;
        public long costMs;
        public boolean hitRoundLimit;
    }

    /** 一次工具调用的轨迹 */
    public static class ToolTrace {
        public int round;
        public String name;
        public Map<String, String> args;
        public String result;
        public long costMs;
    }
}
