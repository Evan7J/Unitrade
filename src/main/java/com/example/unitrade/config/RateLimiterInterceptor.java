package com.example.unitrade.config;

import com.example.unitrade.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口限流拦截器 —— 三档速率，底层是 Redis 分布式令牌桶（见 {@link RedisRateLimiter}）。
 *
 * <h2>三档速率为什么这样分</h2>
 * <table border="1">
 *   <caption>限流档位</caption>
 *   <tr><th>档位</th><th>速率</th><th>理由</th></tr>
 *   <tr><td>写接口</td><td>3/s</td><td>写操作会落库，且多数涉及金额/状态流转，天然低频</td></tr>
 *   <tr><td>读接口</td><td>20/s</td><td>列表页可能连发若干请求，需要容忍正常浏览节奏</td></tr>
 *   <tr><td>AI 对话</td><td>5/s</td><td><b>成本敏感</b>：每次都是一个真实的 LLM 调用，20/s 等于把钱包敞开</td></tr>
 * </table>
 *
 * <p>改造前 AI 对话走的是"读接口"档（20/s）——理由是"它只查询和生成草稿，不写库"。
 * 这个理由只考虑了<b>数据库压力</b>，漏掉了<b>按 token 计费</b>这件事：
 * 一次 LLM 调用比一次数据库查询贵好几个数量级，两者的限流阈值不该相同。
 * 这类"防护维度选错了"的问题不会报错，只会在账单上体现。
 */
@Component
@RequiredArgsConstructor
public class RateLimiterInterceptor implements HandlerInterceptor {

    /** 读接口：每秒 20 个请求 */
    private static final int READ_RATE = 20;

    /** 写接口：每秒 3 个请求 */
    private static final int WRITE_RATE = 3;

    /** AI 对话：每秒 5 个请求（成本敏感，见类注释） */
    private static final int AI_RATE = 5;

    private final RedisRateLimiter redisRateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();
        String method = request.getMethod();

        int rate;
        String tier;
        if (uri.startsWith("/api/agent/")) {
            rate = AI_RATE;
            tier = "ai";
        } else if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
            rate = WRITE_RATE;
            tier = "write";
        } else {
            rate = READ_RATE;
            tier = "read";
        }

        // 桶容量取与速率相同的值：等价于"允许攒下 1 秒的突发量"，
        // 与原 Guava RateLimiter 的平滑体感接近，不会出现"第一秒就放开 20 个"的尖峰。
        if (!redisRateLimiter.tryAcquire(ip + ":" + tier, rate, rate)) {
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
