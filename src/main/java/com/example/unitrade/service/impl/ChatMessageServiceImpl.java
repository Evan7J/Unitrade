package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.ChatMessage;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.ChatMessageMapper;
import com.example.unitrade.mapper.UserMapper;
import com.example.unitrade.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final UserMapper userMapper;
    private final com.example.unitrade.mapper.ProductMapper productMapper;

    @Override
    public ChatMessage send(ChatMessage message) {
        message.setIsRead(0);
        message.setMessageType(message.getMessageType() != null ? message.getMessageType() : "text");
        chatMessageMapper.insert(message);
        return message;
    }

    @Override
    public List<ChatMessage> getHistory(Long userId, Long targetUserId, Long productId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                .and(w1 -> w1.eq(ChatMessage::getSenderId, userId).eq(ChatMessage::getReceiverId, targetUserId))
                .or(w2 -> w2.eq(ChatMessage::getSenderId, targetUserId).eq(ChatMessage::getReceiverId, userId))
        );
        if (productId != null) wrapper.eq(ChatMessage::getProductId, productId);
        wrapper.orderByAsc(ChatMessage::getCreateTime);
        return chatMessageMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> getConversations() {
        Long userId = JwtInterceptor.getCurrentUserId();
        List<ChatMessage> allMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .and(w -> w.eq(ChatMessage::getSenderId, userId).or().eq(ChatMessage::getReceiverId, userId))
                        .orderByDesc(ChatMessage::getCreateTime)
        );
        Map<Long, ChatMessage> latestMap = new LinkedHashMap<>();
        for (ChatMessage msg : allMessages) {
            Long otherId = msg.getSenderId().equals(userId) ? msg.getReceiverId() : msg.getSenderId();
            if (!latestMap.containsKey(otherId)) latestMap.put(otherId, msg);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, ChatMessage> entry : latestMap.entrySet()) {
            User other = userMapper.selectById(entry.getKey());
            ChatMessage msg = entry.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("userId", other != null ? other.getId() : entry.getKey());
            map.put("nickname", other != null ? other.getNickname() : "未知用户");
            map.put("avatarUrl", other != null ? other.getAvatarUrl() : null);
            map.put("lastMessage", msg.getContent());
            map.put("lastTime", msg.getCreateTime());
            Long unread = chatMessageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSenderId, entry.getKey())
                            .eq(ChatMessage::getReceiverId, userId)
                            .eq(ChatMessage::getIsRead, 0)
            );
            map.put("unreadCount", unread);
            // 附带商品信息（闲鱼风格：会话列表展示商品缩略图和价格）
            if (msg.getProductId() != null) {
                map.put("productId", msg.getProductId());
                com.example.unitrade.entity.Product product = productMapper.selectById(msg.getProductId());
                if (product != null) {
                    map.put("productTitle", product.getTitle());
                    map.put("productPrice", product.getPrice());
                    map.put("productStatus", product.getStatus());
                    if (product.getImages() != null && !product.getImages().isEmpty()) {
                        map.put("productCover", product.getImages().split(",")[0]);
                    }
                }
            }
            result.add(map);
        }
        return result;
    }

    @Override
    public void markRead(Long senderId) {
        Long userId = JwtInterceptor.getCurrentUserId();
        List<ChatMessage> unread = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSenderId, senderId)
                        .eq(ChatMessage::getReceiverId, userId)
                        .eq(ChatMessage::getIsRead, 0)
        );
        for (ChatMessage msg : unread) {
            msg.setIsRead(1);
            chatMessageMapper.updateById(msg);
        }
    }

    @Override
    public Long getUnreadCount() {
        Long userId = JwtInterceptor.getCurrentUserId();
        return chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getReceiverId, userId)
                        .eq(ChatMessage::getIsRead, 0)
        );
    }
}