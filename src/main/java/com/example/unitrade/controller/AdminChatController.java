package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.ChatMessage;
import com.example.unitrade.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final ChatMessageMapper chatMessageMapper;

    @GetMapping("/list")
    public Result<Page<ChatMessage>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return Result.success(chatMessageMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ChatMessage>().orderByDesc(ChatMessage::getCreateTime)));
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { chatMessageMapper.deleteById(id); return Result.success("删除成功"); }
}