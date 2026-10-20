package com.ape.smarthedge_risk_hub.es.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.io.IOException;

/**
 * RestHighLevelClient 配置。
 * 客户端版本必须与 ES 服务端严格一致（properties 里锁 7.16.2），否则请求会因版本校验被拒。
 */
@Configuration
public class EsConfig {

    private static final Logger log = LoggerFactory.getLogger(EsConfig.class);

    private RestHighLevelClient client;

    @Bean
    public RestHighLevelClient restHighLevelClient(EsProperties props) {
        String[] parts = props.getHosts().split(",");
        HttpHost[] hosts = new HttpHost[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] hp = parts[i].trim().split(":");
            hosts[i] = new HttpHost(hp[0], hp.length > 1 ? Integer.parseInt(hp[1]) : 9200, "http");
        }

        RestClientBuilder builder = RestClient.builder(hosts)
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(props.getConnectTimeoutMs())
                        .setSocketTimeout(props.getSocketTimeoutMs()));

        if (props.getUsername() != null && !props.getUsername().isEmpty()) {
            CredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(provider));
        }

        client = new RestHighLevelClient(builder);
        log.info("[es] RestHighLevelClient 初始化完成, hosts={}", props.getHosts());
        return client;
    }

    /**
     * 让容器关闭时释放连接，避免测试或重启时连接泄漏。
     */
    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
                log.info("[es] RestHighLevelClient 已关闭");
            } catch (IOException e) {
                log.warn("[es] 关闭客户端失败: {}", e.getMessage());
            }
        }
    }

    /** 统一的操作选项：不要求服务端返回完整分片信息，减少网络开销 */
    public static RequestOptions commonOptions() {
        return RequestOptions.DEFAULT;
    }
}
