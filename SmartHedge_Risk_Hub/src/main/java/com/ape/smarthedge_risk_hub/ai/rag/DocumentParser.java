package com.ape.smarthedge_risk_hub.ai.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 文档解析：把上传的文件抽成纯文本。
 *
 * 支持：pdf / txt / md / markdown
 *
 * 两个真实会踩的坑，这里都处理了：
 * 1. **扫描件 PDF**：没有文字层，抽出来是空串。必须报明确错误，否则会建出一个
 *    「上传成功但检索不到」的空文档 —— 这种问题最难查。
 * 2. **中文 txt 编码**：Windows 上的 txt 常是 GBK，按 UTF-8 读会变乱码，
 *    乱码再向量化就彻底废了。这里做 UTF-8 严格校验，失败自动退 GBK。
 */
@Component
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /** 解析结果：正文 + 页数（txt 为 null） */
    public static class ParsedDoc {
        public final String text;
        public final Integer pages;

        public ParsedDoc(String text, Integer pages) {
            this.text = text;
            this.pages = pages;
        }
    }

    public ParsedDoc parse(File file, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return parsePdf(file);
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return new ParsedDoc(parsePlainText(file), null);
        }
        throw new IllegalArgumentException("不支持的文件类型：" + fileName
                + "（仅支持 pdf / txt / md）");
    }

    private ParsedDoc parsePdf(File file) {
        PDDocument document = null;
        try {
            document = PDDocument.load(file);

            if (document.isEncrypted()) {
                throw new IllegalArgumentException("该 PDF 已加密，无法解析。请先去掉密码保护再上传。");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // 按位置排序，否则双栏/表格 PDF 抽出来的文字顺序会错乱
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "该 PDF 没有可提取的文字层（可能是扫描件/图片版）。"
                                + "请改用带文字层的 PDF，或先用 OCR 转成文本再上传。");
            }
            log.info("[rag] PDF 解析完成: {} 页", document.getNumberOfPages());
            return new ParsedDoc(text, document.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("该 PDF 需要密码，无法解析。", e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败：" + e.getMessage(), e);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (Exception ignore) {
                    // 关闭失败不影响解析结果
                }
            }
        }
    }

    /**
     * 读纯文本。先按 UTF-8 严格解码，失败再试 GBK，最后兜底 UTF-8 宽松模式。
     * 直接用 new String(bytes, UTF_8) 不会抛异常，只会静默产出乱码，所以必须显式校验。
     */
    private String parsePlainText(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String text = strictDecode(bytes, StandardCharsets.UTF_8);
            if (text == null) {
                text = strictDecode(bytes, Charset.forName("GBK"));
                if (text != null) {
                    log.info("[rag] 文本文件按 GBK 解码成功: {}", file.getName());
                }
            }
            if (text == null) {
                text = new String(bytes, StandardCharsets.UTF_8);
                log.warn("[rag] UTF-8/GBK 均校验失败，已按宽松 UTF-8 读取: {}", file.getName());
            }
            if (text.trim().isEmpty()) {
                throw new IllegalArgumentException("文件内容为空");
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文本文件读取失败：" + e.getMessage(), e);
        }
    }

    /** 严格解码：非法字节返回 null 而不是替换成 U+FFFD */
    private String strictDecode(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 支持的类型，用于上传校验时给出可读提示 */
    public static List<String> supportedExtensions() {
        return Arrays.asList("pdf", "txt", "md", "markdown");
    }
}
