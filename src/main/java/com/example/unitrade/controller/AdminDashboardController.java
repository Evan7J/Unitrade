package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final ChatMessageMapper chatMessageMapper;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> map = new HashMap<>();
        map.put("userCount", userMapper.selectCount(null));
        map.put("productCount", productMapper.selectCount(null));
        map.put("orderCount", orderMapper.selectCount(null));
        map.put("messageCount", chatMessageMapper.selectCount(null));
        return Result.success(map);
    }
}