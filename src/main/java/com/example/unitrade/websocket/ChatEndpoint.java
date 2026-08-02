package com.example.unitrade.websocket;

import com.example.unitrade.entity.ChatMessage;
import com.example.unitrade.service.ChatMessageService;
import com.example.unitrade.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 聊天端点
 *
 * 使用 Jackson ObjectMapper 序列化消息为 JSON，替代手动拼接字符串
 * 相比手拼 JSON 的好处：
 *   1. 自动转义特殊字符（如引号、换行），防止 JSON 注入
 *   2. 正确处理时间格式（JavaTimeModule 支持 LocalDateTime）
 *   3. 代码可读性好，易于维护
 */
@Component
@ServerEndpoint("/ws/chat/{token}")
public class ChatEndpoint {

    private static ChatMessageService chatMessageService;
    private static JwtUtil jwtUtil;
    private static final Map<Long, Session> ONLINE_USERS = new ConcurrentHashMap<>();

    /** Jackson 序列化器，支持 Java 8 时间类型 */
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Autowired
    public void setChatMessageService(ChatMessageService service) { ChatEndpoint.chatMessageService = service; }
    @Autowired
    public void setJwtUtil(JwtUtil util) { ChatEndpoint.jwtUtil = util; }

    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            ONLINE_USERS.put(userId, session);
        } catch (Exception e) { /* token invalid */ }
    }

    @OnClose
    public void onClose(Session session, @PathParam("token") String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            ONLINE_USERS.remove(userId);
        } catch (Exception e) { /* ignore */ }
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("token") String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            // message format: "receiverId:content:productId:messageType"
            String[] parts = message.split(":", 4);
            Long receiverId = Long.valueOf(parts[0]);
            String content = parts[1];
            Long productId = parts.length > 2 && !parts[2].isEmpty() ? Long.valueOf(parts[2]) : null;
            String messageType = parts.length > 3 ? parts[3] : "text";

            ChatMessage msg = new ChatMessage();
            msg.setSenderId(userId);
            msg.setReceiverId(receiverId);
            msg.setProductId(productId);
            msg.setContent(content);
            msg.setMessageType(messageType);
            chatMessageService.send(msg);

            Session receiverSession = ONLINE_USERS.get(receiverId);
            if (receiverSession != null && receiverSession.isOpen()) {
                // 用 Jackson 序列化消息为 JSON，自动处理转义和时间格式
                String json = objectMapper.writeValueAsString(msg);
                receiverSession.getBasicRemote().sendText(json);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @OnError
    public void onError(Session session, Throwable error) { error.printStackTrace(); }
}