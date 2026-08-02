package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.ChatMessage;
import com.example.unitrade.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatMessageService chatMessageService;

    @GetMapping("/history/{targetUserId}")
    public Result<List<ChatMessage>> getHistory(@PathVariable Long targetUserId, @RequestParam(required = false) Long productId) {
        Long userId = JwtInterceptor.getCurrentUserId();
        return Result.success(chatMessageService.getHistory(userId, targetUserId, productId));
    }

    @GetMapping("/conversations")
    public Result<List<Map<String, Object>>> getConversations() {
        return Result.success(chatMessageService.getConversations());
    }

    @PutMapping("/read/{senderId}")
    public Result<?> markRead(@PathVariable Long senderId) {
        chatMessageService.markRead(senderId);
        return Result.success("已标记已读");
    }

    @GetMapping("/unread")
    public Result<Long> getUnreadCount() {
        return Result.success(chatMessageService.getUnreadCount());
    }
}