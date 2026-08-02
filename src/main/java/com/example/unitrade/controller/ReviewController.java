package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.common.Result;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.Review;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.ReviewMapper;
import com.example.unitrade.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewMapper reviewMapper;
    private final UserMapper userMapper;

    // 提交评价
    @PostMapping("/save")
    public Result<?> save(@RequestBody Review review) {
        review.setReviewerId(JwtInterceptor.getCurrentUserId());
        review.setCreateTime(java.time.LocalDateTime.now());
        reviewMapper.insert(review);
        return Result.success("评价成功");
    }

    // 获取某用户收到的评价
    @GetMapping("/user/{userId}")
    public Result<List<Map<String, Object>>> getUserReviews(@PathVariable Long userId) {
        List<Review> reviews = reviewMapper.selectList(
            new LambdaQueryWrapper<Review>().eq(Review::getRevieweeId, userId).orderByDesc(Review::getCreateTime)
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            map.put("createTime", r.getCreateTime());
            User reviewer = userMapper.selectById(r.getReviewerId());
            map.put("nickname", reviewer != null ? reviewer.getNickname() : "匿名");
            map.put("avatarUrl", reviewer != null ? reviewer.getAvatarUrl() : null);
            result.add(map);
        }
        return Result.success(result);
    }

    // 检查当前用户对某订单是否已评价（买家和卖家各自独立评价）
    @GetMapping("/check/{orderId}")
    public Result<Map<String, Object>> checkReviewed(@PathVariable Long orderId) {
        Long userId = JwtInterceptor.getCurrentUserId();
        Review review = reviewMapper.selectOne(
            new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, orderId)
                .eq(Review::getReviewerId, userId)
        );
        Map<String, Object> result = new HashMap<>();
        if (review != null) {
            result.put("reviewed", true);
            result.put("rating", review.getRating());
            result.put("content", review.getContent());
        } else {
            result.put("reviewed", false);
        }
        return Result.success(result);
    }
}
