package com.ape.smarthedge_risk_hub.ai;

import com.ape.smarthedge_risk_hub.ai.client.LlmClient;
import com.ape.smarthedge_risk_hub.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 冒烟断言：不加载 Spring 上下文，直接打本地 Ollama。
 * 跑：mvn test -Dtest=AiLlmSmokeTest
 *
 * 默认用 qwen2.5:7b + native 模式（实测支持原生 Function Calling、人设服从度好）；
 * r1 的 prompt 模式解析走纯单测，不联网。
 */
public class AiLlmSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM =
            "你是 SmartHedge 大宗商品智能问答助手，只回答大宗商品、套期保值与风险管理相关问题，"
                    + "无关问题礼貌拒答并引导回主题。";

    private LlmClient client(String model, String toolMode) {
        AiProperties props = new AiProperties();
        props.getLlm().setBaseUrl("http://localhost:11434");
        props.getLlm().setModel(model);
        props.getLlm().setToolMode(toolMode);
        props.getLlm().setConnectTimeoutSeconds(10);
        props.getLlm().setReadTimeoutSeconds(180);
        return new LlmClient(props);
    }

    /** 主用配置 */
    private LlmClient client() {
        return client("qwen2.5:7b", "native");
    }

    /**
     * 断言 1：人设生效 —— 无关提问必须在开头 60 字内出现拒答/引导，
     * 只看开头是为了杜绝「先写完诗、末尾补一句引导」蒙混过关。
     */
    @Test
    void personaShouldRejectOffTopicQuestion() {
        assumeOllamaAlive();
        String answer = chat("写一首关于春天的诗，要押韵");
        print("off-topic", answer);
        String head = answer.length() > 60 ? answer.substring(0, 60) : answer;
        boolean rejected = head.contains("大宗商品") || head.contains("套期保值") || head.contains("风险管理")
                || head.contains("偏离") || head.contains("无关") || head.contains("无法")
                || head.contains("抱歉") || head.contains("助手");
        assertTrue(rejected, "人设未生效：回答开头没有拒答引导，实际开头=" + head);
    }

    /** 断言 2：领域问题应给出有效、切题的回答 */
    @Test
    void domainQuestionShouldBeAnswered() {
        assumeOllamaAlive();
        String answer = chat("什么叫做基差？用一句话解释");
        print("domain", answer);
        assertTrue(answer.length() > 10, "回答过短，疑似未接通模型：" + answer);
        assertTrue(answer.contains("现货") || answer.contains("期货"), "领域回答偏离主题：" + answer);
    }

    /** 断言 3：native 模式必须真实返回工具调用（qwen2.5 原生 Function Calling 能力） */
    @Test
    void nativeModeShouldReturnToolCall() {
        assumeOllamaAlive();
        List<LlmClient.ChatMessage> messages = new ArrayList<LlmClient.ChatMessage>();
        messages.add(LlmClient.ChatMessage.system(SYSTEM));
        messages.add(LlmClient.ChatMessage.user("RB2410 这个期货合约的最后交易日规则是什么？"));

        List<LlmClient.ToolDef> tools = new ArrayList<LlmClient.ToolDef>();
        tools.add(new LlmClient.ToolDef("queryFuturesContractSpec",
                "查询期货合约条款：交易单位、报价单位、涨跌停板、最低保证金、最后交易日规则、交割日期规则")
                .param("contractCode", "合约代码，如 RB2410、CU2408")
                .required("contractCode"));

        LlmClient.LlmReply reply = client().chat(messages, tools);
        assertTrue(reply.hasToolCall(), "未返回工具调用，content=" + reply.getContent());
        assertEquals("queryFuturesContractSpec", reply.getToolName());
        assertEquals("RB2410", reply.getToolArgs().get("contractCode"));
    }

    /** 断言 4（纯解析、不联网）：prompt 模式剥离思考块与代码围栏，抽出工具调用，并丢弃模型伪造的 result */
    @Test
    void promptModeShouldParseToolJson() {
        String raw = buildRawResponse("<think>用户想知道合约条款，应该调用工具。</think>\n"
                + "```json\n"
                + "{\"tool\":\"queryFuturesContractSpec\",\"args\":{\"contractCode\":\"RB2410\"},"
                + "\"result\":{\"lastTradeDate\":\"2024-05-12\"}}\n"
                + "```");

        LlmClient.LlmReply reply = client("deepseek-r1:7b", "prompt").parsePromptReply(raw);
        assertTrue(reply.hasToolCall(), "未解析出工具调用");
        assertEquals("queryFuturesContractSpec", reply.getToolName());
        Map<String, String> args = reply.getToolArgs();
        assertNotNull(args);
        assertEquals("RB2410", args.get("contractCode"));
    }

    /** 断言 5（纯解析）：tool=none 时应取出 answer 正文 */
    @Test
    void promptModeShouldParsePlainAnswer() {
        String raw = buildRawResponse("{\"tool\":\"none\",\"answer\":\"基差=现货价格-期货价格\"}");
        LlmClient.LlmReply reply = client("deepseek-r1:7b", "prompt").parsePromptReply(raw);
        assertFalse(reply.hasToolCall(), "不应识别为工具调用");
        assertEquals("基差=现货价格-期货价格", reply.getContent());
    }

    private String chat(String question) {
        List<LlmClient.ChatMessage> messages = new ArrayList<LlmClient.ChatMessage>();
        messages.add(LlmClient.ChatMessage.system(SYSTEM));
        messages.add(LlmClient.ChatMessage.user(question));
        return client().chat(messages).getContent();
    }

    private void print(String tag, String text) {
        String oneLine = text == null ? "" : text.replace("\r", " ").replace("\n", " ");
        System.out.println("[" + tag + "] " + oneLine);
    }

    private String buildRawResponse(String content) {
        try {
            ObjectNode message = MAPPER.createObjectNode();
            message.put("content", content);
            ObjectNode choice = MAPPER.createObjectNode();
            choice.set("message", message);
            ObjectNode root = MAPPER.createObjectNode();
            root.putArray("choices").add(choice);
            return root.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assumeOllamaAlive() {
        boolean alive;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:11434/api/tags").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            alive = conn.getResponseCode() == 200;
            conn.disconnect();
        } catch (Exception e) {
            alive = false;
        }
        Assumptions.assumeTrue(alive, "本地 Ollama 未启动，跳过联网断言");
    }
}
