package com.ape.smarthedge_risk_hub.ai.rag;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量化客户端：把文本转成向量，走 Ollama 的批量接口 {@code POST /api/embed}。
 *
 * 为什么用 /api/embed 而不是老的 /api/embeddings：
 * 老接口一次只吃一条，一个 200 块的文档要发 200 次 HTTP；批量接口一次能带一批，
 * 实测快一个数量级。Ollama 0.1.26+ 才有这个接口。
 *
 * 失败策略：这里**不吞异常**。向量化失败必须让上层知道，把文档标成 FAILED，
 * 否则会出现「文档显示入库成功、实际检索不到」这种最难查的问题。
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final AiProperties props;
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public EmbeddingClient(AiProperties props) {
        this.props = props;
        this.rest = buildRestTemplate(props);
    }

    public EmbeddingClient(AiProperties props, RestTemplate rest) {
        this.props = props;
        this.rest = rest;
    }

    private static RestTemplate buildRestTemplate(AiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 向量化是 CPU 密集的，本地 7B 级模型批量跑可能几十秒，读超时要给足
        int readTimeout = props.getLlm().getReadTimeoutSeconds() * 1000;
        int connectTimeout = props.getLlm().getConnectTimeoutSeconds() * 1000;
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        RestTemplate template = new RestTemplate(factory);
        // 默认 StringHttpMessageConverter 是 ISO-8859-1，中文会乱码
        for (int i = 0; i < template.getMessageConverters().size(); i++) {
            if (template.getMessageConverters().get(i) instanceof StringHttpMessageConverter) {
                template.getMessageConverters().set(i, new StringHttpMessageConverter(StandardCharsets.UTF_8));
                break;
            }
        }
        return template;
    }

    /** 单条向量化，用于查询侧 */
    public float[] embedOne(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        List<float[]> list = embed(Collections.singletonList(text));
        if (list.isEmpty()) {
            throw new IllegalStateException("向量化返回为空");
        }
        return list.get(0);
    }

    /**
     * 批量向量化。内部按 batchSize 分批，避免单次请求过大超时。
     * 返回顺序与入参严格一致。
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<float[]>();
        if (texts == null || texts.isEmpty()) {
            return result;
        }
        int batchSize = Math.max(1, props.getRag().getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            List<String> batch = texts.subList(from, to);
            long start = System.currentTimeMillis();
            List<float[]> part = embedBatch(batch);
            log.info("[rag] 向量化 {}-{}/{} 条, cost={}ms",
                    from + 1, to, texts.size(), System.currentTimeMillis() - start);
            result.addAll(part);
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch) {
        String url = props.getLlm().getBaseUrl() + "/api/embed";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getRag().getEmbeddingModel());
        ArrayNode input = body.putArray("input");
        for (String t : batch) {
            // 超长文本截断，避免模型报错；单块本就不该超长（分块已控 500 字）
            input.add(t == null ? "" : t);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        HttpEntity<String> entity = new HttpEntity<String>(body.toString(), headers);

        ResponseEntity<String> resp;
        try {
            resp = rest.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("调用向量化服务失败（模型="
                    + props.getRag().getEmbeddingModel() + "）：" + e.getMessage(), e);
        }

        JsonNode embeddings = parseEmbeddings(resp.getBody());
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != batch.size()) {
            throw new IllegalStateException("向量化返回条数与入参不一致：期望 " + batch.size()
                    + " 实际 " + (embeddings == null ? "null" : embeddings.size()));
        }

        int expectDim = props.getRag().getEmbeddingDim();
        List<float[]> vectors = new ArrayList<float[]>(batch.size());
        for (JsonNode arr : embeddings) {
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            if (v.length != expectDim) {
                throw new IllegalStateException("向量维度不匹配：模型返回 " + v.length
                        + " 维，配置 ai.rag.embedding-dim=" + expectDim
                        + "，pgvector 表是 vector(" + expectDim + ")。请统一后再重建向量。");
            }
            vectors.add(v);
        }
        return vectors;
    }

    /** 兼容两种返回体：新版 {"embeddings":[[...]]}，老版单条 {"embedding":[...]} */
    private JsonNode parseEmbeddings(String raw) {
        try {
            JsonNode root = mapper.readTree(raw == null ? "" : raw);
            JsonNode embeddings = root.get("embeddings");
            if (embeddings != null && embeddings.isArray()) {
                return embeddings;
            }
            JsonNode single = root.get("embedding");
            if (single != null && single.isArray()) {
                ArrayNode wrapped = mapper.createArrayNode();
                wrapped.add(single);
                return wrapped;
            }
            log.warn("[rag] 向量化响应无法解析: {}", raw);
            return null;
        } catch (Exception e) {
            log.warn("[rag] 向量化响应解析失败: {}", raw);
            return null;
        }
    }

    /** 探活：向量化服务是否可用（知识库上传前自检用） */
    public boolean isAvailable() {
        try {
            embedOne("健康检查");
            return true;
        } catch (Exception e) {
            log.warn("[rag] 向量化服务不可用: {}", e.getMessage());
            return false;
        }
    }
}
