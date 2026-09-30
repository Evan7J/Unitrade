package com.example.unitrade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Milvus 可用性探测条件 —— 把"向量库能不能用"变成装配期的一个判断。
 *
 * <h2>为什么不做一个需要手工配置的开关</h2>
 * "手动改 yml 里的 enabled=false 才能启动"这种设计要求人记住一件事，
 * 而人一定会忘：换台机器、面试现场演示、同事拉代码——只要 Milvus 没起，
 * 应用就直接起不来，第一印象就崩了。
 *
 * <p>改成<b>自动探测</b>之后：「Milvus 在 → 启用语义召回；不在 → 自动降级为关键词检索」，
 * 零配置、两条路都能跑通。这是"<b>能降级的功能不要做成硬依赖</b>"的具体落地。
 *
 * <h2>为什么用 TCP 探测而不是 try-catch 真实连接</h2>
 * 真实连接要等 gRPC 的 10 秒 deadline（日志里就是这个数），
 * 每次启动都白等 10 秒。TCP connect 1.5 秒内就能给出结论，
 * 而且不产生任何 Milvus 侧副作用。
 */
public class MilvusAvailabilityCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(MilvusAvailabilityCondition.class);

    static final String ENABLED_KEY = "app.vector.enabled";
    static final String HOST_KEY = "spring.ai.vectorstore.milvus.client.host";
    static final String PORT_KEY = "spring.ai.vectorstore.milvus.client.port";

    private static final int PROBE_TIMEOUT_MS = 1500;
    private static final int DEFAULT_PORT = 19530;

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return probe(context.getEnvironment());
    }

    /**
     * 探测结果缓存。
     *
     * <p>为什么要缓存：同一个 Condition 会被 Spring 在装配期评估多次
     * （两个互补的配置类 + 框架内部的重复评估，实测启动时触发了 4 次），
     * 不缓存就会打印 4 条一模一样的降级日志，把真正的启动信息淹掉。
     *
     * <p>为什么缓存是安全的：条件只在<b>启动装配期</b>有意义，
     * 那时 Milvus 的状态不会在毫秒级内变化。运行期 Milvus 掉线属于另一个问题
     * （由调用点的降级与告警负责），不该由这里兜。
     */
    private static volatile Boolean cachedResult;

    /**
     * 探测 Milvus 是否可达。
     *
     * @return true = 可用，应当装配真实向量库
     */
    static boolean probe(Environment env) {
        Boolean cached = cachedResult;
        if (cached != null) {
            return cached;
        }
        synchronized (MilvusAvailabilityCondition.class) {
            if (cachedResult != null) {
                return cachedResult;
            }
            boolean result = doProbe(env);
            cachedResult = result;
            return result;
        }
    }

    private static boolean doProbe(Environment env) {
        if (!env.getProperty(ENABLED_KEY, Boolean.class, true)) {
            log.warn("[向量检索] {} = false，语义召回将退化为纯关键词检索", ENABLED_KEY);
            return false;
        }

        String host = env.getProperty(HOST_KEY, "localhost");
        int port = parsePort(env.getProperty(PORT_KEY));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            log.info("[向量检索] Milvus {}:{} 可达，启用语义召回", host, port);
            return true;
        } catch (IOException e) {
            log.warn("[向量检索降级] Milvus {}:{} 不可达（{}）—— 语义召回退化为纯关键词检索，"
                            + "搜索与发布等核心功能不受影响。"
                            + "启动 Milvus（在项目根目录执行 docker compose up -d）后重启即可恢复。",
                    host, port, e.getMessage());
            return false;
        }
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[向量检索] 配置的 port 不是合法数字（{}），回退为 {}", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    /**
     * 互补条件：Milvus 不可达时装配降级实现。
     *
     * <p>用"取反的独立条件"而不是 {@code @ConditionalOnMissingBean}，
     * 是因为后者依赖 bean 定义的注册顺序，在自定义配置类里不如自动配置中可靠。
     * 两个互补条件各自独立判断，谁先谁后都不会错。
     */
    public static class Unavailable extends MilvusAvailabilityCondition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !probe(context.getEnvironment());
        }
    }
}
