package com.example.unitrade.agent;

import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.entity.Category;
import com.example.unitrade.service.CategoryService;
import com.example.unitrade.service.ProductService;
import com.example.unitrade.vo.ProductListVO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI 助手的工具集，用 spring-ai 的 @Tool 注册给大模型调用，
 * 不再手写 tools JSON 和 tool_calls 解析。
 *
 * 工具执行结果会作为 tool message 回填给模型；同时把本次会话的
 * 草稿 / 商品结果暂存（volatile 保证流式场景下 Reactor 工作线程可见），
 * 供 AgentService 在对话结束时装配进返回给前端的数据。
 */
@Component
@RequiredArgsConstructor
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final ProductService productService;
    private final CategoryService categoryService;
    private final VectorStore vectorStore;

    /** 语义召回补足的商品上限 */
    private static final int SEMANTIC_TOP_K = 8;

    /** 工具结果按会话隔离：key=sessionId，避免多用户并发时互相串数据 */
    private final Map<String, SessionResult> results = new ConcurrentHashMap<>();

    /** 清理指定会话的残留结果 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            results.remove(sessionId);
        }
    }

    /** 取走并清空指定会话的工具结果；不存在返回 null */
    public SessionResult consume(String sessionId) {
        return sessionId == null ? null : results.remove(sessionId);
    }

    /** 记录一次工具执行结果到指定会话 */
    private void record(List<ProductListVO> products, ProductPublishDTO draft, ToolContext toolContext) {
        String sid = contextSession(toolContext);
        if (sid == null) {
            return;
        }
        results.compute(sid, (k, existing) -> {
            if (existing == null) {
                existing = new SessionResult();
            }
            if (products != null) {
                existing.products = products;
            }
            if (draft != null) {
                existing.draft = draft;
            }
            return existing;
        });
    }

    private String contextSession(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get("sessionId");
        return value == null ? null : String.valueOf(value);
    }

    /** 单次会话的工具结果载体 */
    public static class SessionResult {
        public volatile ProductPublishDTO draft;
        public volatile List<ProductListVO> products;
    }

    /**
     * 商品搜索工具【关键词搜索 + 语义召回融合】。
     * 先用 MySQL 关键词跑主结果，再用本地向量库召回补齐未命中的相关商品。
     */
    @Tool(name = "searchProducts", description = "搜索平台在售的二手商品，用于帮用户找商品、比价、推荐。")
    public List<ProductListVO> searchProducts(
            @ToolParam(description = "搜索关键词，可为空", required = false) String keyword,
            @ToolParam(description = "最低价格（元），可为空", required = false) BigDecimal minPrice,
            @ToolParam(description = "最高价格（元），可为空", required = false) BigDecimal maxPrice,
            @ToolParam(description = "分类ID，可为空", required = false) Long categoryId,
            ToolContext toolContext) {

        ProductQueryDTO query = new ProductQueryDTO();
        query.setKeyword(keyword);
        if (minPrice != null) {
            query.setMinPrice(minPrice);
        }
        if (maxPrice != null) {
            query.setMaxPrice(maxPrice);
        }
        if (categoryId != null) {
            query.setCategoryId(categoryId);
        }
        query.setPage(1);
        query.setSize(20);

        List<ProductListVO> main = productService.pageQuery(query).getRecords();
        List<ProductListVO> supplement = semanticComplement(keyword, query, main);

        // 合并并去重（按商品ID）
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        List<ProductListVO> merged = new ArrayList<>();
        for (ProductListVO vo : main) {
            if (ids.add(vo.getId())) {
                merged.add(vo);
            }
        }
        for (ProductListVO vo : supplement) {
            if (ids.add(vo.getId())) {
                merged.add(vo);
            }
        }

        record(merged, null, toolContext);
        return merged;
    }

    @Tool(name = "listCategories", description = "获取平台所有商品分类的ID和名称。")
    public List<Category> listCategories() {
        return categoryService.listAll();
    }

    /**
     * 一键发布工具：从自然语言抽取商品参数生成发布草稿。
     */
    @Tool(name = "draftProduct", description = "根据用户想出售/发布商品的自然语言描述，抽取商品信息并生成发布草稿。当用户表达要卖、发布、上架、出售某个商品时使用。")
    public ProductPublishDTO draftProduct(
            @ToolParam(description = "商品标题，一句话概括商品核心信息，例如 iPhone 15 128G 黑色") String title,
            @ToolParam(description = "商品描述，包含成色、购买时间、使用情况、有无瑕疵等", required = false) String description,
            @ToolParam(description = "售价（元）") BigDecimal price,
            @ToolParam(description = "原价（元），可省略", required = false) BigDecimal originalPrice,
            @ToolParam(description = "成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹", required = false) Integer productCondition,
            @ToolParam(description = "分类名称，如 数码产品、书籍教材、服饰鞋包、运动户外、其他") String categoryName,
            @ToolParam(description = "物流方式：1面交无需邮寄 2付邮邮寄 3包邮，默认3", required = false) Integer shippingType,
            ToolContext toolContext) {

        ProductPublishDTO dto = new ProductPublishDTO();

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("商品标题不能为空");
        }
        dto.setTitle(title);
        dto.setDescription(description);

        if (price == null) {
            throw new IllegalArgumentException("售价不能为空");
        }
        dto.setPrice(price);
        if (originalPrice != null) {
            dto.setOriginalPrice(originalPrice);
        }

        if (productCondition != null) {
            dto.setProductCondition(productCondition);
        }
        dto.setShippingType(shippingType != null ? shippingType : 3);

        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        dto.setCategoryId(matchCategory(categoryName));

        record(null, dto, toolContext);
        return dto;
    }

    private Long matchCategory(String name) {
        List<Category> categories = categoryService.listAll();
        for (Category category : categories) {
            String categoryName = category.getName();
            if (categoryName.equals(name) || categoryName.contains(name) || name.contains(categoryName)) {
                return category.getId();
            }
        }
        String available = categories.stream().map(Category::getName).collect(Collectors.joining("、"));
        throw new IllegalArgumentException("无法匹配分类「" + name + "」，请从以下分类中选择：" + available);
    }

    /**
     * 用本地向量库做语义召回，按价格筛选后返回不在主结果列表里的商品。
     * 向量库不可用（索引未构建 / 异常）时返回空列表，即退化为 MySQL 关键词搜索，不影响可用性。
     */
    private List<ProductListVO> semanticComplement(String keyword, ProductQueryDTO query,
                                                   List<ProductListVO> main) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(keyword).topK(SEMANTIC_TOP_K).build());
            if (hits.isEmpty()) {
                return List.of();
            }
            Collection<Long> idList = hits.stream()
                    .map(doc -> Long.valueOf(String.valueOf(doc.getMetadata().get("productId"))))
                    .toList();

            Set<Long> mainIds = main.stream().map(ProductListVO::getId).collect(Collectors.toSet());
            return productService.listByIds(idList).stream()
                    .filter(vo -> !mainIds.contains(vo.getId()))
                    .filter(vo -> priceMatch(vo, query))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("语义召回失败，退化为关键词搜索：{}", e.getMessage());
            return List.of();
        }
    }

    private boolean priceMatch(ProductListVO vo, ProductQueryDTO query) {
        if (vo.getPrice() == null) {
            return false;
        }
        if (query.getMinPrice() != null && vo.getPrice().compareTo(query.getMinPrice()) < 0) {
            return false;
        }
        if (query.getMaxPrice() != null && vo.getPrice().compareTo(query.getMaxPrice()) > 0) {
            return false;
        }
        return true;
    }
}