package com.example.unitrade.agent;

import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.entity.Category;
import com.example.unitrade.service.CategoryService;
import com.example.unitrade.service.ProductService;
import com.example.unitrade.vo.ProductListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final DeepSeekClient deepSeekClient;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    private static final int MAX_ROUNDS = 8;

    public AgentReply chat(String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userMessage));

        List<Map<String, Object>> tools = buildTools();
        ProductPublishDTO draft = null;
        List<ProductListVO> products = null;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<String, Object> response = deepSeekClient.chat(messages, tools);
            Map<String, Object> message = extractMessage(response);
            List<Map<String, Object>> toolCalls = extractToolCalls(message);

            if (toolCalls.isEmpty()) {
                AgentReply reply = new AgentReply();
                Object content = message.get("content");
                reply.setReply(content == null ? "" : content.toString());
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
        reply.setReply("抱歉，这次处理有点复杂，请换个说法再试试。");
        reply.setDraft(draft);
        reply.setProducts(products);
        return reply;
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

    private List<ProductListVO> searchProducts(Map<String, Object> args) throws Exception {
        ProductQueryDTO query = new ProductQueryDTO();
        if (args.get("keyword") != null) {
            query.setKeyword(args.get("keyword").toString());
        }
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
        return productService.pageQuery(query).getRecords();
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
        draftProps.put("categoryName", Map.of("type", "string", "description", "分类名称，如 数码产品、书籍教材、服饰鞋包、生活用品、运动户外、其他"));
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