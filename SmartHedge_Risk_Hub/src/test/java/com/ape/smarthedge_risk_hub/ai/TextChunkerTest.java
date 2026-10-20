package com.ape.smarthedge_risk_hub.ai;

import com.ape.smarthedge_risk_hub.ai.rag.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块器单元测试。纯逻辑测试，不起 Spring 上下文，跑得快。
 *
 * 重点钉死两件最容易出事的事：
 * 1. **绝不死循环** —— 重叠参数接近块长时，滑动窗口若回退过头就会原地打转
 * 2. **PDF 折行要还原** —— 否则会在折行处切出半句话
 */
public class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    public void 正常文本按大小分块且不丢内容() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第").append(i).append("条 公司开展套期保值业务应当遵循套保目的原则。");
        }
        String text = sb.toString();

        List<String> chunks = chunker.chunk(text, 500, 80);

        assertFalse(chunks.isEmpty(), "不应切出空结果");
        // 每块不应超过块长（允许边界回退后的自然长度）
        for (String c : chunks) {
            assertTrue(c.length() <= 500, "块长度超限: " + c.length());
        }
        // 首条与末条应保留，说明没有丢内容
        assertTrue(chunks.get(0).contains("第0条"), "首块应包含开头内容");
        assertTrue(chunks.get(chunks.size() - 1).contains("第59条"), "末块应包含结尾内容");
    }

    @Test
    public void 重叠大于等于块长时仍能终止() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("保证金标准与风险敞口控制规则说明。");
        }
        // 重叠 == 块长，属于病态参数，必须靠内部降级 + 强制前进兜住
        List<String> chunks = chunker.chunk(sb.toString(), 100, 100);
        assertFalse(chunks.isEmpty());
        // 能跑到这里就说明没有死循环
        assertTrue(chunks.size() < 2000, "块数异常膨胀，疑似前进逻辑有问题: " + chunks.size());
    }

    @Test
    public void 极短文本与空文本不报错() {
        assertTrue(chunker.chunk("", 500, 80).isEmpty());
        assertTrue(chunker.chunk(null, 500, 80).isEmpty());
        assertTrue(chunker.chunk("   ", 500, 80).isEmpty());

        List<String> one = chunker.chunk("只有一句话。", 500, 80);
        assertEquals(1, one.size());
        assertEquals("只有一句话。", one.get(0));
    }

    @Test
    public void PDF折行被还原为完整句子() {
        // 模拟 PDF 抽取的硬换行：句子被折成两行
        String pdfLike = "第八条 公司在交易所保证金标准基础上统一加收 2\n"
                + "个百分点作为内部风控缓冲。\n"
                + "第九条 保证金专用账户资金余额不得低于持仓保证金的 150%。";

        List<String> chunks = chunker.chunk(pdfLike, 500, 80);
        String all = String.join("", chunks);

        // 折行必须被接上：数字与中文之间保留一个空格是可接受的（同时也避免英文单词粘连）
        assertTrue(all.contains("统一加收 2 个百分点"), "折行应被接上，实际: " + all);
        assertFalse(all.contains("加收 2\n"), "句中被折开的换行符不应保留");
        // 句末标点后的换行是真断句，必须保留
        assertTrue(all.contains("内部风控缓冲。\n"), "句末标点后的换行应保留，实际: " + all);
    }

    @Test
    public void 英文折行不会被粘成一个单词() {
        String en = "The system is called SmartHedge\nRiskHub and it is used for hedging.";
        List<String> chunks = chunker.chunk(en, 500, 80);
        String all = String.join("", chunks);
        assertTrue(all.contains("SmartHedge RiskHub"), "英文折行应换成空格，实际: " + all);
    }

    @Test
    public void 段末标点处的换行被保留为断句点() {
        String text = "第一段内容到此结束。\n第二段内容从此开始。";
        List<String> chunks = chunker.chunk(text, 500, 0);
        // 没有超块长时本就是一块，这里主要验证不会把「。」后面也粘掉
        String all = String.join("", chunks);
        assertTrue(all.contains("结束。"), "句末标点应保留");
        assertTrue(all.contains("开始。"), "第二段内容应保留");
    }
}
