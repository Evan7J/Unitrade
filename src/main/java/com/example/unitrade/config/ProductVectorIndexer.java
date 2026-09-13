package com.example.unitrade.config;

import com.example.unitrade.entity.Category;
import com.example.unitrade.entity.Product;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 启动时把全量在售商品构建成向量索引（替代原 Python 的 index 构建）。
 * 索引构建好后持久化到磁盘 JSON，下次启动直接加载，避免重复计算。
 * 任何一步失败都只降级（语义召回退化为关键词搜索），不影响主流程启动。
 */
@Component
@RequiredArgsConstructor
public class ProductVectorIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductVectorIndexer.class);

    private final VectorStore vectorStore;
    private final ProductMapper productMapper;
    private final CategoryService categoryService;

    @Value("${app.vector.index-file:${user.dir}/data/product-vector-index.json}")
    private String indexFile;

    @Override
    public void run(ApplicationArguments args) {
        try {
            File file = new File(indexFile);
            if (file.exists()) {
                if (vectorStore instanceof SimpleVectorStore simpleStore) {
                    simpleStore.load(file);
                    log.info("商品向量索引已从本地加载：{}", indexFile);
                    return;
                }
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

            vectorStore.add(docs);

            if (vectorStore instanceof SimpleVectorStore simpleStore) {
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                simpleStore.save(file);
                log.info("商品向量索引构建完成并持久化：{}（{} 条）", indexFile, docs.size());
            }
        } catch (Exception e) {
            log.warn("商品向量索引构建失败，语义召回将退化为关键词搜索：{}", e.getMessage());
        }
    }
}