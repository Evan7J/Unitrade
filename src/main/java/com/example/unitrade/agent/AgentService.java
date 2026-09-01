package com.example.unitrade.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.entity.AgentMessage;
import com.example.unitrade.entity.Category;
import com.example.unitrade.mapper.AgentMessageMapper;
import com.example.unitrade.service.CategoryService;
import com.example.unitrade.service.ProductService;
import com.example.unitrade.vo.ProductListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final DeepSeekClient deepSeekClient;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;
    private final AgentMessageMapper agentMessageMapper;
    private final SemanticSearchClient semanticSearchClient;

    private static final int MAX_ROUNDS = 8;

    /** 单次会话从历史载入的最大消息条数 */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** 单次会话重建上下文的最大字符数（超出则裁剪最旧的） */
    private static final int MAX_HISTORY_CHARS = 6000;
    /** 语义召回补足的商品上限 */
    private static final int SEMANTIC_TOP_K = 8;

    /**
     * 多轮对话入口【带会话持久化】
     *
     * @param sessionId   会话ID，null 或空则由本方法生成新会话；前端下次带上即可续接上下文
     * @param userMessage 用户消息
     */
    public AgentReply chat(String sessionId, String userMessage) {
        String sid = resolveSessionId(sessionId);

        // 1. 记录本条用户消息，并载入该会话历史做上下文
        saveMessage(sid, "user", userMessage);
        List<Map<String, Object>> messages = buildMessages(sid, userMessage);

        List<Map<String, Object>> tools = buildTools();
        ProductPublishDTO draft = null;
        List<ProductListVO> products = null;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<String, Object> response = deepSeekClient.chat(messages, tools);
            Map<String, Object> message = extractMessage(response);
            List<Map<String, Object>> toolCalls = extractToolCalls(message);

            if (toolCalls.isEmpty()) {
                String text = message.get("content") == null ? "" : message.get("content").toString();
                // 2. 落库最终文本回复，构成可续接的历史
                saveMessage(sid, "assistant", text);
                AgentReply reply = new AgentReply();
                reply.setSessionId(sid);
                reply.setReply(text);
                reply.setDraft(draft);
                reply.setProducts(products);
                return reply;
            }

            messages.add(message);

            for (Map<String, Object> toolCall : toolCalls) {
                String id = (String) toolCall.get("id");
                Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                String name = (String) function.get("name");
                String arguments = (String) function.get("arguments");
                ToolResult toolResult = executeTool(name, arguments);
                if (toolResult.draft != null) {
                    draft = toolResult.draft;
                }
                if (toolResult.products != null) {
                    products = toolResult.products;
                }
                messages.add(Map.of("role", "tool", "tool_call_id", id, "content", toolResult.content));
            }
        }
        AgentReply reply = new AgentReply();
        reply.setSessionId(sid);
        reply.setReply("抱歉，这次处理有点复杂，请换个说法再试试。");
        reply.setDraft(draft);
        reply.setProducts(products);
        return reply;
    }

    /**
     * 生成或沿用会话ID。
     * 会话以 sessionId 为隔离粒度：不同 sessionId 的上下文互不相通。
     */
    private String resolveSessionId(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            return sessionId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 组装发给模型的消息列表：system 提示 + 裁剪后的历史 + 当前用户消息。
     *
     * 历史只重放 user/assistant 纯文本对话，不含带工具调用的中间消息，
     * 从而避免模型要求缺失的 tool 结果导致上下文校验失败。
     */
    private List<Map<String, Object>> buildMessages(String sessionId, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        List<AgentMessage> history = loadTrimmedHistory(sessionId);
        for (AgentMessage m : history) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /**
     * 载入该会话历史并按上下文窗口裁剪：
     * 先按时间升序取最近 MAX_HISTORY_MESSAGES 条，再从最旧丢弃，直到字符数不超上限。
     */
    private List<AgentMessage> loadTrimmedHistory(String sessionId) {
        List<AgentMessage> all = agentMessageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getSessionId, sessionId)
                        .orderByAsc(AgentMessage::getCreateTime)
        );
        List<AgentMessage> recent = all.size() > MAX_HISTORY_MESSAGES
                ? all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size())
                : all;

        List<AgentMessage> result = new ArrayList<>(recent);
        int total = result.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length()).sum();
        while (total > MAX_HISTORY_CHARS && result.size() > 1) {
            AgentMessage removed = result.remove(0);
            total -= removed.getContent() == null ? 0 : removed.getContent().length();
        }
        return result;
    }

    private void saveMessage(String sessionId, String role, String content) {
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreateTime(LocalDateTime.now());
        agentMessageMapper.insert(msg);
    }

    private static final String SYSTEM_PROMPT = """
            你是「UniTrade 校园二手交易平台」的 AI 闲置助手。用简体中文、口语化、简洁地回复用户。
            回复里不要使用 emoji 表情，不要使用 Markdown 符号（比如 #、*、-、---、``` 等），
            用自然的文字段落表达即可。搜索结果里商品的详细信息和跳转入口由前端渲染卡片展示，
            你只需要用一两句话概括推荐，不用把商品的每个字段都罗列一遍。
            """;

    private Map<String, Object> extractMessage(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        return (Map<String, Object>) choices.get(0).get("message");
    }

    private List<Map<String, Object>> extractToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        if (toolCalls == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) toolCalls;
    }

    /**
     * 工具执行结果，content 回填给模型，draft 是发布场景生成的草稿，products 是搜索到的商品
     */
    private static class ToolResult {
        String content;
        ProductPublishDTO draft;
        List<ProductListVO> products;
    }

    private ToolResult executeTool(String name, String arguments) {
        ToolResult result = new ToolResult();
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            switch (name) {
                case "searchProducts" -> {
                    List<ProductListVO> products = searchProducts(args);
                    result.products = products;
                    result.content = objectMapper.writeValueAsString(products);
                }
                case "listCategories" -> result.content = objectMapper.writeValueAsString(categoryService.listAll());
                case "draftProduct" -> {
                    ProductPublishDTO dto = draftProduct(args);
                    result.draft = dto;
                    result.content = objectMapper.writeValueAsString(dto);
                }
                default -> result.content = "未知工具：" + name;
            }
        } catch (Exception e) {
            result.content = "工具执行失败：" + e.getMessage();
        }
        return result;
    }

    /**
     * 商品搜索工具【RAG 语义召回 + 关键词融合】
     *
     * 先按原有关键词/同义词跑 MySQL 分页搜索拿主结果；
     * 再用语义检索服务召回相关商品 ID，把未出现在主结果里的商品补足到尾部。
     * 语义服务不可用时，recall 返回空列表，结果即退化为原有 MySQL 搜索，不影响可用性。
     */
    private List<ProductListVO> searchProducts(Map<String, Object> args) throws Exception {
        String keyword = args.get("keyword") == null ? "" : args.get("keyword").toString();

        ProductQueryDTO query = new ProductQueryDTO();
        query.setKeyword(keyword);
        if (args.get("minPrice") != null) {
            query.setMinPrice(new BigDecimal(args.get("minPrice").toString()));
        }
        if (args.get("maxPrice") != null) {
            query.setMaxPrice(new BigDecimal(args.get("maxPrice").toString()));
        }
        if (args.get("categoryId") != null) {
            query.setCategoryId(Long.valueOf(args.get("categoryId").toString()));
        }
        query.setPage(1);
        query.setSize(20);

        // 1) 主结果：MySQL 关键词 + 同义词
        List<ProductListVO> main = productService.pageQuery(query).getRecords();

        // 2) 语义补充：检索服务可按语义召回，即使长句不命中关键词也能兜底
        List<ProductListVO> supplement = semanticComplement(keyword, query, main);

        // 3) 合并并去重（按商品ID）
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
        return merged;
    }

    /**
     * 用语义检索服务召回商品，按价格筛选后返回不在主力结果里的商品。
     */
    private List<ProductListVO> semanticComplement(String keyword, ProductQueryDTO query,
                                                   List<ProductListVO> main) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        List<Number> ids = semanticSearchClient.recall(keyword, SEMANTIC_TOP_K);
        if (ids.isEmpty()) {
            return List.of();
        }
        Collection<Long> idList = ids.stream().map(Number::longValue).toList();

        Set<Long> mainIds = main.stream().map(ProductListVO::getId).collect(Collectors.toSet());
        return productService.listByIds(idList).stream()
                .filter(vo -> !mainIds.contains(vo.getId()))
                .filter(vo -> priceMatch(vo, query))
                .collect(Collectors.toList());
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

    /**
     * 从自然语言抽取的商品参数生成发布草稿
     *
     * 模型只负责给分类名称，这里根据名称精确/模糊匹配真实分类 ID，
     * 保证草稿里的 categoryId 一定是数据库里存在的值。
     */
    private ProductPublishDTO draftProduct(Map<String, Object> args) {
        ProductPublishDTO dto = new ProductPublishDTO();

        String title = str(args.get("title"));
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("商品标题不能为空");
        }
        dto.setTitle(title);
        dto.setDescription(str(args.get("description")));

        if (args.get("price") == null) {
            throw new IllegalArgumentException("售价不能为空");
        }
        dto.setPrice(new BigDecimal(args.get("price").toString()));
        if (args.get("originalPrice") != null) {
            dto.setOriginalPrice(new BigDecimal(args.get("originalPrice").toString()));
        }

        if (args.get("productCondition") != null) {
            dto.setProductCondition(((Number) args.get("productCondition")).intValue());
        }
        dto.setShippingType(args.get("shippingType") != null
                ? ((Number) args.get("shippingType")).intValue() : 3);

        String categoryName = str(args.get("categoryName"));
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        dto.setCategoryId(matchCategory(categoryName));

        return dto;
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
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

    private List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        Map<String, Object> searchProps = new LinkedHashMap<>();
        searchProps.put("keyword", Map.of("type", "string", "description", "搜索关键词，可为空"));
        searchProps.put("minPrice", Map.of("type", "number", "description", "最低价格（元），可为空"));
        searchProps.put("maxPrice", Map.of("type", "number", "description", "最高价格（元），可为空"));
        searchProps.put("categoryId", Map.of("type", "integer", "description", "分类ID，可为空"));

        tools.add(buildTool("searchProducts", "搜索平台在售的二手商品，用于帮用户找商品、比价、推荐。",
                objectSchema(searchProps, List.of())));

        tools.add(buildTool("listCategories", "获取平台所有商品分类的ID和名称。",
                Map.of("type", "object", "properties", Map.of())));

        Map<String, Object> draftProps = new LinkedHashMap<>();
        draftProps.put("title", Map.of("type", "string", "description", "商品标题，一句话概括商品核心信息，例如 iPhone 15 128G 黑色"));
        draftProps.put("description", Map.of("type", "string", "description", "商品描述，包含成色、购买时间、使用情况、有无瑕疵等"));
        draftProps.put("price", Map.of("type", "number", "description", "售价（元）"));
        draftProps.put("originalPrice", Map.of("type", "number", "description", "原价（元），可省略"));
        draftProps.put("productCondition", Map.of("type", "integer", "description", "成色：1全新 2几乎全新 3轻微使用痕迹 4明显使用痕迹"));
        draftProps.put("categoryName", Map.of("type", "string", "description", "分类名称，如 数码产品、书籍教材、服饰鞋包、运动户外、其他"));
        draftProps.put("shippingType", Map.of("type", "integer", "description", "物流方式：1面交无需邮寄 2付邮邮寄 3包邮，默认3"));

        tools.add(buildTool("draftProduct",
                "根据用户想出售/发布商品的自然语言描述，抽取商品信息并生成发布草稿。当用户表达要卖、发布、上架、出售某个商品时使用。",
                objectSchema(draftProps, List.of("title", "price", "categoryName"))));

        return tools;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> buildTool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}