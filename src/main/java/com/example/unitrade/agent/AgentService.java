package com.example.unitrade.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.entity.AgentMessage;
import com.example.unitrade.mapper.AgentMessageMapper;
import com.example.unitrade.vo.ProductListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentService {

    /** 流式响应用来携带草稿/商品结果的特殊事件前缀 */
    public static final String META_PREFIX = "__META__";

    private final ChatClient.Builder chatClientBuilder;
    private final AgentTools agentTools;
    private final AgentMessageMapper agentMessageMapper;
    private final ObjectMapper objectMapper;

    /** 单次会话从历史载入的最大消息条数 */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** 单次会话重建上下文的最大字符数（超出则裁剪最旧的） */
    private static final int MAX_HISTORY_CHARS = 6000;

    /**
     * 多轮对话入口【一次性返回，兼容原接口】。
     * 用 spring-ai ChatClient 的 .tools() 让框架自动完成 tool calling 循环，
     * 最终文字回复放 reply，草稿/商品结果由 AgentTools 暂存并透传出来。
     */
    public AgentReply chat(String sessionId, String userMessage) {
        String sid = resolveSessionId(sessionId);

        List<Message> history = buildHistoryMessages(sid);
        saveMessage(sid, "user", userMessage);

        agentTools.clear(sid);
        try {
            ChatClient.ChatClientRequestSpec request = newRequest(history);
            String text = request.user(userMessage).tools(agentTools)
                    .toolContext(Map.of("sessionId", sid)).call().content();

            String response = text == null ? "" : text;
            saveMessage(sid, "assistant", response);

            AgentTools.SessionResult result = agentTools.consume(sid);
            return reply(sid, response, toDraft(result), toProducts(result));
        } catch (Exception e) {
            String fallback = "抱歉，这次没聊明白，请换个说法再试试。";
            saveMessage(sid, "assistant", fallback);
            AgentTools.SessionResult result = agentTools.consume(sid);
            return reply(sid, fallback, toDraft(result), toProducts(result));
        }
    }

    private ProductPublishDTO toDraft(AgentTools.SessionResult result) {
        return result == null ? null : result.draft;
    }

    private List<ProductListVO> toProducts(AgentTools.SessionResult result) {
        return result == null ? null : result.products;
    }

    /**
     * 多轮对话入口【流式输出，SSE 逐字推送】。
     * 文本逐块下发，全部生成完后追加一个 __META__ 事件，携带本次的草稿/商品结果。
     */
    public Flux<String> streamText(String sessionId, String userMessage) {
        String sid = resolveSessionId(sessionId);

        List<Message> history = buildHistoryMessages(sid);
        saveMessage(sid, "user", userMessage);

        agentTools.clear(sid);
        ChatClient.ChatClientRequestSpec request = newRequest(history);

        // 累积流式文本，对话结束时写库，保证多轮上下文里 assistant 历史连续
        StringBuilder sb = new StringBuilder();
        Flux<String> content = request.user(userMessage).tools(agentTools)
                .toolContext(Map.of("sessionId", sid)).stream().content()
                .doOnNext(sb::append);

        Flux<String> meta = Flux.defer(() -> {
            if (!StringUtils.hasText(sb.toString())) {
                saveMessage(sid, "assistant", "抱歉，我没理解你的意思。");
                return Flux.concat(Flux.just("抱歉，我没理解你的意思。"), emitMeta(sid));
            }
            saveMessage(sid, "assistant", sb.toString());
            return emitMeta(sid);
        });

        return Flux.concat(content, meta)
                .onErrorResume(e -> Flux.just("抱歉，网络开小差了，请稍后再试。"));
    }

    private ChatClient.ChatClientRequestSpec newRequest(List<Message> history) {
        ChatClient.ChatClientRequestSpec request = chatClientBuilder.build()
                .prompt()
                .system(SYSTEM_PROMPT);
        if (!history.isEmpty()) {
            request.messages(history);
        }
        return request;
    }

    private Flux<String> emitMeta(String sid) {
        try {
            AgentTools.SessionResult result = agentTools.consume(sid);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sessionId", sid);
            meta.put("draft", toDraft(result));
            meta.put("products", toProducts(result));
            return Flux.just(META_PREFIX + objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    private AgentReply reply(String sid, String text, ProductPublishDTO draft, List<ProductListVO> products) {
        AgentReply reply = new AgentReply();
        reply.setSessionId(sid);
        reply.setReply(text);
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
     * 组装发给模型的历史消息，只重放 user/assistant 纯文本对话。
     * 工具调用的中间消息由 spring-ai 每次生成，不需要从数据库重放。
     */
    private List<Message> buildHistoryMessages(String sessionId) {
        List<AgentMessage> trimmed = loadTrimmedHistory(sessionId);
        List<Message> messages = new ArrayList<>(trimmed.size());
        for (AgentMessage m : trimmed) {
            if ("user".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            } else {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }
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
}