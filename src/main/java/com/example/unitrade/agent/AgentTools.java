package com.example.unitrade.agent;

import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.entity.Category;
import com.example.unitrade.search.RrfFuser;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI 助手的工具集，用 spring-ai 的 @Tool 注册给大模型调用，
 * 不再手写 tools JSON 和 tool_calls 解析。
 *
 * 工具执行结果会作为 tool message 回填给模型；同时把本次会话的
 * 草稿 / 商品结果按 sessionId 暂存（ConcurrentHashMap 隔离），
 * 供 AgentService 在本会话对话结束时装配进返回给前端的数据。
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

    /**
     * 向量库 metadata 里存商品 id 的 key。
     *
     * <p>⚠️ 必须与写入侧 {@code ProductVectorIndexer} 里的 {@code meta.put("productId", ...)}
     * 保持一致 —— 这类"两个文件靠字符串约定达成一致"的耦合，编译期查不出来。
     */
    private static final String PRODUCT_ID_KEY = "productId";

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
     * 商品搜索工具【关键词 + 向量双路召回 → RRF 融合重排】。
     *
     * <h2>为什么是 RRF，而不是"关键词结果 + 向量结果追加在后面"</h2>
     * 追加式合并有个隐蔽的问题：关键词路返回 20 条就把位置占满了，
     * 向量路召回的长尾商品只能排在 20 名开外 —— <b>第二路等于白召</b>。
     * 而闲置商品的标题是个人自由填写的，"同义异形"特别多
     * （比如"机械键盘"可能被写成 "keyboard 手感很好"），
     * 恰恰是纯关键词召回不到、只能靠向量捞回来的那批。
     * <p>RRF 只看排名不看分数，让"两路都靠前"的文档浮上来，
     * 单路第一不再一票通吃 —— 详见 {@link RrfFuser} 的类注释。
     *
     * <h2>降级行为</h2>
     * 向量路无结果（含 Milvus 不可用、关键词为空）时<b>直接返回关键词结果</b>，
     * 不做任何重排 —— 刻意保持降级前后的排序一致，否则上线排查时
     * "为什么这次顺序不一样"会变成一个查不出来的问题。
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

        // 第 1 路：MySQL 关键词（分类/价格等结构化筛选也在这里生效）
        List<ProductListVO> keywordHits = productService.pageQuery(query).getRecords();
        // 第 2 路：向量语义召回
        List<ProductListVO> vectorHits = semanticHits(keyword, query);

        List<ProductListVO> merged = fuseByRrf(keywordHits, vectorHits);
        record(merged, null, toolContext);
        return merged;
    }

    /**
     * 把两路结果按 RRF 融合成一个有序列表。
     *
     * <p>注意这里<b>不再把"已在关键词结果里的商品"从向量路里剔除</b>：
     * 一篇商品同时出现在两路，正是"共识"信号，剔除掉反而丢掉了融合最有价值的输入。
     */
    private List<ProductListVO> fuseByRrf(List<ProductListVO> keywordHits,
                                          List<ProductListVO> vectorHits) {
        if (vectorHits.isEmpty()) {
            return keywordHits;
        }

        // 融合只操作 id，最后再映射回 VO；用 putIfAbsent 保证同一 id 只保留一份
        Map<Long, ProductListVO> byId = new HashMap<>();
        for (ProductListVO vo : keywordHits) {
            byId.putIfAbsent(vo.getId(), vo);
        }
        for (ProductListVO vo : vectorHits) {
            byId.putIfAbsent(vo.getId(), vo);
        }

        List<List<Long>> rankedLists = List.of(idsOf(keywordHits), idsOf(vectorHits));
        List<ProductListVO> fused = new ArrayList<>();
        for (Long id : RrfFuser.fuse(rankedLists)) {
            ProductListVO vo = byId.get(id);
            if (vo != null) {
                fused.add(vo);
            }
        }
        return fused;
    }

    private List<Long> idsOf(List<ProductListVO> vos) {
        List<Long> ids = new ArrayList<>(vos.size());
        for (ProductListVO vo : vos) {
            ids.add(vo.getId());
        }
        return ids;
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
     * 向量路召回：返回<b>按语义相似度排序</b>的商品（已按价格条件过滤）。
     *
     * <p>与之前的实现有两个关键区别：
     * <ol>
     *   <li><b>不再剔除"已在关键词结果里的商品"</b>。同时出现在两路是"共识"信号，
     *       剔除掉就等于把 RRF 最有价值的输入扔了；</li>
     *   <li><b>显式按分数降序排一遍，不依赖 similaritySearch 的返回顺序</b>。
     *       RRF 的输入是"排名"，排名错了整个融合就错了。</li>
     * </ol>
     *
     * <p>向量库不可用（Milvus 挂了 / 索引异常）时返回空列表，
     * 调用方会自然退化为纯关键词检索 —— 核心搜索零中断。
     */
    private List<ProductListVO> semanticHits(String keyword, ProductQueryDTO query) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(keyword).topK(SEMANTIC_TOP_K).build());
            if (hits.isEmpty()) {
                return List.of();
            }

            // 先过滤掉没有 productId 元数据的文档，再排序 ——
            // 否则排名里会夹着无效项，把真实商品的位次整体推后
            List<Document> ranked = new ArrayList<>(hits.size());
            for (Document doc : hits) {
                if (doc.getMetadata().get(PRODUCT_ID_KEY) != null) {
                    ranked.add(doc);
                }
            }
            ranked.sort(Comparator.comparingDouble(
                            (Document doc) -> doc.getScore() == null
                                    ? Double.NEGATIVE_INFINITY
                                    : doc.getScore())
                    .reversed());

            List<Long> rankedIds = new ArrayList<>(ranked.size());
            for (Document doc : ranked) {
                rankedIds.add(Long.valueOf(String.valueOf(doc.getMetadata().get(PRODUCT_ID_KEY))));
            }

            Map<Long, ProductListVO> byId = new HashMap<>();
            for (ProductListVO vo : productService.listByIds(rankedIds)) {
                byId.put(vo.getId(), vo);
            }

            // ⚠️ 查库回来后必须按向量排名重新对齐：listByIds 的返回顺序不保证
            List<ProductListVO> result = new ArrayList<>(rankedIds.size());
            for (Long id : rankedIds) {
                ProductListVO vo = byId.get(id);
                if (vo != null && priceMatch(vo, query)) {
                    result.add(vo);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("语义召回失败，退化为纯关键词检索：{}", e.getMessage());
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