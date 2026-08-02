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
 * 使用 Guava RateLimiter 对每个 IP 做请求限流，读操作 20/s，写操作 3/s
 */
@Component
public class RateLimiterInterceptor implements HandlerInterceptor {

    /** 默认限流速率：每秒 20 个请求 */
    private static final double DEFAULT_RATE = 20.0;

    /** 写操作限流速率：每秒 3 个请求 */
    private static final double WRITE_RATE = 3.0;

    /** 每个 IP 对应一个 RateLimiter */
    private final Map<String, RateLimiter> limiterMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();

        String method = request.getMethod();
        double rate = ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))
                ? WRITE_RATE : DEFAULT_RATE;

        String key = ip + ":" + (rate == WRITE_RATE ? "write" : "read");
        RateLimiter limiter = limiterMap.computeIfAbsent(key, k -> RateLimiter.create(rate));

        if (!limiter.tryAcquire()) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        return true;
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
