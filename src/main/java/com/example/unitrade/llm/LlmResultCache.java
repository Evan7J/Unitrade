package com.example.unitrade.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 结果缓存 —— 按「内容哈希」缓存，同样的输入不重复花钱。
 *
 * <h2>什么能缓存、什么绝对不能</h2>
 * 判据只有一条：<b>这段代码是不是"纯映射"</b> —— 相同输入必然产生相同输出。
 * <ul>
 *   <li>✅ <b>意图分类</b>：同一个句子永远是同一个标签；</li>
 *   <li>✅ <b>参数抽取</b>：同一句口语永远抽出同一组条件；</li>
 *   <li>✅ <b>话术生成</b>：prompt 里已固定商品/价格/分支/轮次，属确定性映射；</li>
 *   <li>❌ <b>报价计算</b>：<b>绝对禁止缓存</b>。报价依赖会话状态（轮次、上一轮报价），
 *       而"上一轮报价"会被人工接管改写 —— 缓存一旦命中旧值，就会出现"报价回升"
 *       这种击穿单调性的结果。报价是纯函数、不调模型，本来就没有理由给它加缓存。</li>
 * </ul>
 *
 * <h2>为什么 key 里必须带模型名</h2>
 * 缓存的是"某个模型对某段 prompt 的输出"。换了模型，同一段 prompt 的输出就变了。
 * key 不带模型名，切模型之后会读到上一个模型的答案 ——
 * 这是那种<b>不报错、只是结果慢慢变怪</b>的问题。
 *
 * <h2>两级存储：Redis 优先，内存兜底</h2>
 * <ul>
 *   <li><b>Redis</b>：多实例共享，是生产形态；</li>
 *   <li><b>进程内内存</b>：Redis 不可用时的降级。</li>
 * </ul>
 * 为什么兜底不是"直接不缓存"：Redis 抖动在生产里很常见，
 * 一旦它挂了就让缓存整体失效，成本会突然涨回去 —— 而这件事<b>不会报错</b>，
 * 只会在月底账单上体现。
 *
 * <p>内存兜底在多实例下会不一致（各实例各存一份），但这不影响正确性：
 * 缓存的只是纯映射结果，最坏情况是"这个实例没命中、又算了一遍"，
 * <b>多花钱，但不会算错</b>。
 */
@Component
public class LlmResultCache {

    private static final Logger log = LoggerFactory.getLogger(LlmResultCache.class);

    private static final String KEY_PREFIX = "llm:cache:";

    /** 内存兜底的上限，防止长时间运行把堆撑爆。 */
    private static final int LOCAL_MAX_ENTRIES = 5000;

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    /** 内存兜底存储（带过期时间）。用 LinkedHashMap 保持插入序，便于按序淘汰。 */
    private final Map<String, LocalEntry> localStore = new LinkedHashMap<>(256, 0.75f, false);

    // 命中统计 —— 成本对照实验要靠它区分"省下来的钱"和"算出来的钱"
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public LlmResultCache(StringRedisTemplate redis,
                          @Value("${llm.cache-enabled:true}") boolean enabled,
                          @Value("${llm.cache-ttl-minutes:120}") long ttlMinutes) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * 取缓存。
     *
     * <p>用 {@link Optional} 而不是 null：调用方必须显式处理"没命中"的情况，
     * 而"命中了一个空字符串"和"没命中"是两件不同的事。
     */
    public Optional<String> get(String namespace, String fingerprint) {
        if (!enabled) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        String key = key(namespace, fingerprint);
        try {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                hits.incrementAndGet();
                return Optional.of(value);
            }
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("[缓存降级] Redis 读取失败，改用进程内缓存：{}", e.getMessage());
            Optional<String> local = getLocal(key);
            if (local.isPresent()) {
                hits.incrementAndGet();
                return local;
            }
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /** 写缓存。写失败只告警 —— 缓存写不进去不该让业务失败。 */
    public void put(String namespace, String fingerprint, String value) {
        if (!enabled || value == null) {
            return;
        }
        String key = key(namespace, fingerprint);
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("[缓存降级] Redis 写入失败，改写入进程内缓存：{}", e.getMessage());
            putLocal(key, value);
        }
    }

    // ── 内存兜底 ──────────────────────────────────────────────────────────

    private synchronized Optional<String> getLocal(String key) {
        LocalEntry entry = localStore.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            localStore.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    private synchronized void putLocal(String key, String value) {
        if (localStore.size() >= LOCAL_MAX_ENTRIES) {
            evict();
        }
        localStore.put(key, new LocalEntry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    /**
     * 淘汰。
     *
     * <p>先清过期的；如果清完还是满的，就按插入顺序丢掉最旧的一批。
     * 这是"故意做得简单"的一处：内存兜底只在 Redis 挂掉时生效，
     * 为它引入一个 LRU 库不划算 —— 丢多了只是少省点钱。
     */
    private void evict() {
        Iterator<Map.Entry<String, LocalEntry>> it = localStore.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
        if (localStore.size() >= LOCAL_MAX_ENTRIES) {
            int drop = localStore.size() / 2;
            Iterator<String> keys = localStore.keySet().iterator();
            for (int i = 0; i < drop && keys.hasNext(); i++) {
                keys.next();
                keys.remove();
            }
        }
    }

    private record LocalEntry(String value, long expireAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAtMillis;
        }
    }

    private String key(String namespace, String fingerprint) {
        return KEY_PREFIX + namespace + ":" + fingerprint;
    }

    /**
     * 内容指纹。
     *
     * <p>用 SHA-256 而不是 {@code String.hashCode()}：
     * 后者只有 32 位，几万条记录就会出现碰撞，而<b>碰撞的后果是返回另一个输入的答案</b> ——
     * 这种错误不会有任何报错，只会偶发地答非所问。
     *
     * <p>⚠️ 调用方必须把<b>所有影响输出的输入</b>都放进来（模型名、system prompt、user prompt），
     * 漏掉任何一个都会读到不该命中的缓存。
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                // 分隔符防止 ["ab","c"] 与 ["a","bc"] 撞成同一个指纹
                digest.update((byte) 0x1F);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    public Stats stats() {
        return new Stats(hits.get(), misses.get(), errors.get());
    }

    /** 每轮实验前清零，避免上一条用例的命中数混进来。 */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
        errors.set(0);
    }

    /** 清空内存兜底（实验之间要隔离，否则上一组的命中会串到下一组）。 */
    public synchronized void clearLocal() {
        localStore.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 缓存命中统计。
     *
     * @param hits   命中次数（这些调用没有花钱）
     * @param misses 未命中次数（这些调用真实发生了）
     * @param errors Redis 异常次数
     */
    public record Stats(long hits, long misses, long errors) {

        public long total() {
            return hits + misses;
        }

        public double hitRate() {
            long t = total();
            return t == 0 ? 0.0 : (double) hits / t;
        }
    }
}
