package com.ape.smarthedge_risk_hub.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文文本分块器。
 *
 * 设计要点：
 * 1. **边界优先**：优先在段落（\n\n）处断开，其次是句子（。！？；），最后才硬切。
 *    硬切会把「保证金比例为合约价值的6%」腰斩成「保证金比例为合约价值的」+「6%」，
 *    两块都检索不到，这是分块最常见的翻车方式。
 * 2. **重叠**：相邻块保留 overlap 个字符的上下文，避免答案正好卡在切缝上。
 * 3. **绝对不死循环**：每轮 start 必须严格前进。滑动窗口 + 回退重叠时，
 *    一旦「回退位置 <= 上一轮起点」就强制跳到窗口末尾，宁可少一点重叠也不能卡死。
 */
@Component
public class TextChunker {

    private static final Logger log = LoggerFactory.getLogger(TextChunker.class);

    /** 断句符：中英文标点 + 换行 */
    private static final String SENTENCE_END = "。！？；!?;\n";
    /** 回退寻找边界的比例：至少要有 60% 的窗口内容才值得在边界处断 */
    private static final double MIN_FILL_RATIO = 0.6;

    public List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }
        int size = chunkSize <= 0 ? 500 : chunkSize;
        int ov = overlap < 0 ? 0 : overlap;
        if (ov >= size) {
            log.warn("[rag] chunk-overlap({}) >= chunk-size({})，重叠已自动降为 size/4", ov, size);
            ov = size / 4;
        }

        String normalized = normalize(text);
        int n = normalized.length();
        int start = 0;

        while (start < n) {
            int end = Math.min(start + size, n);

            // 只有还没到文末时才需要在边界处断开
            if (end < n) {
                int minEnd = start + Math.max(1, (int) (size * MIN_FILL_RATIO));
                int boundary = findBoundary(normalized, minEnd, end);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }

            if (end >= n) {
                break;
            }

            // 回退 overlap 形成上下文重叠
            int next = end - ov;
            if (next <= start) {
                next = end;           // 重叠不可行时直接跳到窗口末尾，保证前进
            }
            // 跳过前导空白，避免块以空白开头
            while (next < n && Character.isWhitespace(normalized.charAt(next))) {
                next++;
            }
            if (next <= start) {
                next = start + 1;     // 双保险：无论如何都前进，杜绝死循环
            }
            start = next;
        }

        log.info("[rag] 分块完成: {} 字 -> {} 块 (size={}, overlap={})", n, chunks.size(), size, ov);
        return chunks;
    }

    /**
     * 在 [minEnd, end) 区间内向回寻找最佳断点，返回「断点之后」的下标。
     * 优先段落，其次句子；找不到返回 -1。
     */
    private int findBoundary(String text, int minEnd, int end) {
        // 1) 段落边界（含空行）
        for (int i = end - 1; i >= minEnd; i--) {
            if (text.charAt(i) == '\n' && i > 0 && text.charAt(i - 1) == '\n') {
                return i + 1;
            }
        }
        // 2) 句子边界
        for (int i = end - 1; i >= minEnd; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 归一化。除了统一换行，最重要的是**还原 PDF 硬换行**：
     *
     * PDF 抽取出来的文字是「按视觉行」换行的，一句话常被折成
     * 「…统一加收 2」+「个百分点作为内部风控缓冲」。而分块器把 \n 视为句子边界，
     * 于是就会在折行处切块，切出「加收 2」这种半句话 —— 两块都检索不准。
     *
     * 处理规则：
     * - 行尾已是句末标点（。！？；等）→ 保留换行，这是真断句
     * - 换行两侧是中文（非字母数字）→ 直接接上，不留空格
     * - 换行两侧含英文/数字（如 Risk / Hub）→ 换成空格，避免把单词粘成一个
     */
    private String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace("\r", "\n");
        s = s.replace("\uFEFF", "").replace("\u200B", "");
        s = s.replaceAll("[ \t]+\n", "\n");

        // 中文折行：两侧都不是字母数字，直接拼接
        s = s.replaceAll("(?<![\n。！？；!?;:])(?<![A-Za-z0-9])\\n(?!\\n)(?![A-Za-z0-9])", "");
        // 剩余折行（含英文/数字场景）：换成空格
        s = s.replaceAll("(?<![\n。！？；!?;:])\\n(?!\\n)", " ");

        s = s.replaceAll("\n{3,}", "\n\n");
        s = s.replaceAll("[ \t]{2,}", " ");
        return s.trim();
    }
}
