package com.example.unitrade.service;

import com.example.unitrade.entity.ChatMessage;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息服务接口
 */
public interface ChatMessageService {
    /** 发送消息 */
    ChatMessage send(ChatMessage message);
    /** 获取与某人的聊天记录 */
    List<ChatMessage> getHistory(Long userId, Long targetUserId, Long productId);
    /** 获取聊天列表（最近联系人） */
    List<Map<String, Object>> getConversations();
    /** 标记消息为已读 */
    void markRead(Long senderId);
    /** 获取未读消息数 */
    Long getUnreadCount();
}
