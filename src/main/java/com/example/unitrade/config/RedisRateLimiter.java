package com.example.unitrade.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 基于 Redis + Lua 的令牌桶限流器 —— 替代原来的单机 Guava RateLimiter。
 *
 * <h2>为什么必须换成 Redis</h2>
 * 原来的实现用 {@code ConcurrentHashMap<IP, RateLimiter>} 在<b>单个 JVM</b> 里计数，有三个硬伤：
 * <ol>
 *   <li><b>多实例各限各的</b>：部署 3 个实例，实际放行量是配置值的 3 倍，
 *       "限流"在扩容后自动失效 —— 而扩容恰恰是最需要限流保护的时候；</li>
 *   <li><b>重启清零</b>：攻击者只要等到发版，配额就重置；</li>
 *   <li><b>map 永不清理</b>：每个访问过的 IP 都留下一个 entry，只增不减。
 *       这不仅是内存泄漏，更是<b>可被利用的</b>——伪造大量来源 IP 就能把内存打满。</li>
 * </ol>
 *
 * <h2>为什么用 Lua 而不是"先查后写"</h2>
 * 令牌桶的两步（读取当前令牌数 → 扣减并写回）如果分成两次 Redis 调用，
 * 中间就存在窗口期：并发请求会同时读到"还剩 1 个令牌"然后各自放行。
 * <b>把"读取+判断+扣减"放进一段 Lua，Redis 单线程执行保证原子性</b>，
 * 这和项目里"原子扣减防超卖"是同一个思路。
 *
 * <h2>Redis 故障时为什么放行（fail-open）</h2>
 * 限流保护的是<b>可用性</b>（防止被打垮），不是<b>正确性</b>（不涉及数据一致）。
 * 当 Redis 挂掉时：
 * <ul>
 *   <li>选 fail-close（全部拒绝）→ Redis 一抖，整个站点不可用，故障被放大；</li>
 *   <li>选 fail-open（放行并告警）→ 短时限流失效，但服务还在。</li>
 * </ul>
 * 两者都有代价，这里选后者，但<b>必须打 ERROR 日志</b>——
 * 否则"限流静默失效"永远不会被发现。
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** 限流 key 前缀 */
    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * 令牌桶 Lua 脚本。
     *
     * <p>KEYS[1] = 桶 key；ARGV[1] = 每秒补充令牌数；ARGV[2] = 桶容量；ARGV[3] = 当前毫秒时间戳。
     * 返回 1 放行、0 拒绝。
     *
     * <p>几个细节：
     * <ul>
     *   <li>{@code delta < 0} 的兜底：多实例时钟不可能完全一致，防止负增量把令牌扣成负数；</li>
     *   <li>令牌上限为 capacity：允许一定突发，但不会累积出一个"巨额储备"；</li>
     *   <li>每次都刷新 TTL：<b>这一条顺手解决了原实现的 map 永不清理问题</b>——
     *       桶从满到空需要 capacity/rate 秒，取 2 倍作为过期时间，
     *       长期不活跃的 key 会被 Redis 自动回收。</li>
     * </ul>
     */
    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local vals = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(vals[1])
            local ts = tonumber(vals[2])
            if tokens == nil then
                tokens = capacity
                ts = now
            end

            local delta = now - ts
            if delta < 0 then delta = 0 end
            tokens = math.min(capacity, tokens + delta * rate / 1000)

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', key, math.ceil(capacity / rate * 2000))

            return allowed
            """;

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取一个令牌。
     *
     * @param dimension        限流维度标识（如 "ip:127.0.0.1:write"）
     * @param permitsPerSecond 每秒补充的令牌数
     * @param capacity         桶容量（允许的突发上限）
     * @return true = 放行；false = 应拒绝
     */
    public boolean tryAcquire(String dimension, int permitsPerSecond, int capacity) {
        if (!StringUtils.hasText(dimension) || permitsPerSecond <= 0 || capacity <= 0) {
            return true;
        }
        try {
            Long result = stringRedisTemplate.execute(
                    SCRIPT,
                    List.of(KEY_PREFIX + dimension),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(capacity),
                    String.valueOf(System.currentTimeMillis()));
            return result != null && result == 1L;
        } catch (Exception e) {
            // fail-open：限流失效不阻断业务，但必须留痕（见类注释）
            log.error("限流器执行失败，本次放行（限流暂时失效）：dimension={} err={}", dimension, e.getMessage());
            return true;
        }
    }
}
