package com.example.unitrade.config;

import com.example.unitrade.entity.Category;
import com.example.unitrade.entity.Product;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时把全量在售商品构建成向量索引写入 Milvus。
 * Milvus 自带持久化，重启不丢数据，因此只需在首次（集合为空）时构建。
 * 若需强制重建索引，删除 Milvus 中 unitrade_products 集合后重启即可。
 * 任何一步失败都只降级（语义召回退化为关键词搜索），不影响主流程启动。
 */
@Component
@RequiredArgsConstructor
public class ProductVectorIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductVectorIndexer.class);

    private final VectorStore vectorStore;
    private final ProductMapper productMapper;
    private final CategoryService categoryService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 降级状态下直接跳过。
            // 为什么必须显式判断：NoOpVectorStore 会静默丢弃写入，
            // 如果不判断就继续走完流程，最后会打印"已写入 Milvus（N 条）"——
            // 而实际上一条都没写。**日志声称成功、真实行为是丢弃，比没有日志更危险**，
            // 因为它会让人彻底排除"向量检索"这个排查方向。
            if (vectorStore instanceof NoOpVectorStore) {
                log.warn("向量库处于降级状态（Milvus 不可用），跳过商品向量索引构建；语义召回当前退化为纯关键词检索");
                return;
            }

            if (hasExistingIndex()) {
                log.info("商品向量索引已存在于 Milvus，跳过构建");
                return;
            }

            List<Product> products = productMapper.selectList(null);
            if (products.isEmpty()) {
                return;
            }

            Map<Long, String> categoryNames = categoryService.listAll().stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

            List<Document> docs = new ArrayList<>(products.size());
            for (Product p : products) {
                String category = p.getCategoryId() == null ? ""
                        : categoryNames.getOrDefault(p.getCategoryId(), "");
                String title = p.getTitle() == null ? "" : p.getTitle();
                String description = p.getDescription() == null ? "" : p.getDescription();

                String content = String.join("\n", title, description, category).trim();
                if (content.isEmpty()) {
                    continue;
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("productId", String.valueOf(p.getId()));
                meta.put("title", title);
                docs.add(new Document(content, meta));
            }

            if (!docs.isEmpty()) {
                vectorStore.add(docs);
                log.info("商品向量索引构建完成，已写入 Milvus（{} 条）", docs.size());
            }
        } catch (Exception e) {
            log.warn("商品向量索引构建失败，语义召回将退化为关键词搜索：{}", e.getMessage());
        }
    }

    /**
     * 用一次 topK=1 的探测查询判断 Milvus 集合是否已有数据，避免重复索引。
     */
    private boolean hasExistingIndex() {
        try {
            List<Document> probe = vectorStore.similaritySearch(
                    SearchRequest.builder().query("探测查询").topK(1).build());
            return probe != null && !probe.isEmpty();
        } catch (Exception e) {
            log.debug("探测查询失败，按空集合处理：{}", e.getMessage());
            return false;
        }
    }
}