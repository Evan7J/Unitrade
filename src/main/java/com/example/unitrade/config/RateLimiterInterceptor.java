package com.example.unitrade.config;

import com.example.unitrade.common.BusinessException;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口限流拦截器
 *
 * 使用 Guava RateLimiter 实现令牌桶算法限流：
 * - 每个 IP 每秒最多 N 个请求
 * - 超过限制返回 429 状态码
 *
 * 面试亮点：
 * 1. 令牌桶算法：固定速率产生令牌，请求消耗令牌，无令牌时拒绝
 * 2. IP 维度限流：每个 IP 独立限流，互不影响
 * 3. ConcurrentHashMap 保证线程安全
 *
 * 相比 Redis 方案的优势：
 * - 零外部依赖，Guava 本地内存操作，性能极高
 * - 无需 Redis 连接，降低系统复杂度
 *
 * 限制速率：
 * - 全局接口：每秒 20 个请求（适合正常用户浏览）
 * - 发布/下单等写操作接口：每秒 3 个请求（防止恶意刷单）
 */
@Component
public class RateLimiterInterceptor implements HandlerInterceptor {

    /** 默认限流速率：每秒 20 个请求 */
    private static final double DEFAULT_RATE = 20.0;

    /** 写操作限流速率：每秒 3 个请求 */
    private static final double WRITE_RATE = 3.0;

    /** 每个 IP 对应一个 RateLimiter，ConcurrentHashMap 保证线程安全 */
    private final Map<String, RateLimiter> limiterMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();

        // 判断是否为写操作（POST/PUT/DELETE），使用更严格的限流
        String method = request.getMethod();
        double rate = ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))
                ? WRITE_RATE : DEFAULT_RATE;

        // 每个 IP + 请求类型（读/写）使用独立的限流器
        String key = ip + ":" + (rate == WRITE_RATE ? "write" : "read");
        RateLimiter limiter = limiterMap.computeIfAbsent(key, k -> RateLimiter.create(rate));

        if (!limiter.tryAcquire()) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        return true;
    }

    /**
     * 获取客户端真实 IP
     * 优先从 Nginx 反向代理头获取，其次从请求直接获取
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多层代理时取第一个 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}