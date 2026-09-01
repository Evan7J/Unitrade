package com.example.unitrade.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 调用独立 Python 语义检索服务的客户端。
 *
 * 带降级：一旦语义检索服务不可用（未启动 / 超时 / 报错），把可用性标记为 false，
 * 上游 AgentService 会自动回退到原有 MySQL 关键词搜索，保证主流程不中断。
 */
@Component
public class SemanticSearchClient {

    private final RestClient restClient;
    private final boolean enabled;

    public SemanticSearchClient(@Value("${semantic.search.base-url:http://localhost:8000}") String baseUrl,
                                @Value("${semantic.search.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.restClient = enabled ? RestClient.builder().baseUrl(baseUrl).build() : null;
    }

    /**
     * 语义召回，返回命中的商品 id 列表。服务不可用时返回空列表（由调用方决定降级）。
     */
    @SuppressWarnings("unchecked")
    public List<Number> recall(String query, int topK) {
        if (!enabled || restClient == null) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/search")
                            .queryParam("q", query)
                            .queryParam("top_k", topK)
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> hits = (List<Map<String, Object>>) body.get("hits");
            if (hits == null || hits.isEmpty()) {
                return Collections.emptyList();
            }
            return hits.stream()
                    .map(h -> (Number) h.get("productId"))
                    .toList();
        } catch (Exception e) {
            // 语义服务不可用，降级
            return Collections.emptyList();
        }
    }
}