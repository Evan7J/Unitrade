package com.example.unitrade.negotiation.cost;

import com.example.unitrade.llm.LlmCostMeter;
import com.example.unitrade.llm.LlmResultCache;
import com.example.unitrade.llm.ModelRouter;
import com.example.unitrade.negotiation.graph.IntentClassifier;
import com.example.unitrade.negotiation.graph.LlmIntentClassifier;
import com.example.unitrade.negotiation.talk.LlmTalkGenerator;
import com.example.unitrade.negotiation.talk.LlmUsageRecorder;
import com.example.unitrade.negotiation.talk.NegotiationTalkGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单次议价成本对照实验 —— 回答「分级路由 + 缓存到底省了多少」。
 *
 * <h2>为什么要三组，而不是两组</h2>
 * 完整方案里有两个独立的省钱手段：<b>模型分级路由</b>和<b>结果缓存</b>。
 * 只跑"全强模型"和"完整方案"两组，得到的降幅是两者叠加的结果，
 * 面试官问一句"这 60% 里路由占多少、缓存占多少"就答不上来了。
 * 所以拆成：
 * <table border="1">
 *   <tr><th>组</th><th>配置</th><th>回答什么问题</th></tr>
 *   <tr><td>B1 基线</td><td>全强模型 + 无缓存</td><td>不做任何优化要花多少</td></tr>
 *   <tr><td>B2 纯路由</td><td>分级路由 + 无缓存</td><td>只换模型能省多少</td></tr>
 *   <tr><td>B3 完整</td><td>分级路由 + 缓存</td><td>再加上缓存能省多少</td></tr>
 * </table>
 *
 * <h2>刻意保留的一件事：让缓存"部分命中"</h2>
 * 三个会话用<b>不同的商品和价格</b>，但买家的问法高度重复 —— 这是真实流量的样子。
 * 于是：
 * <ul>
 *   <li><b>意图分类</b>的 prompt 只跟买家消息有关 → 跨会话命中率高；</li>
 *   <li><b>话术生成</b>的 prompt 含商品与价格 → 跨会话几乎必不命中。</li>
 * </ul>
 * 这不是为了好看，而是为了得到一个诚实的结论：
 * <b>缓存的收益几乎全部来自"短文本的重复"，而不是来自"话术"。</b>
 * 如果有人跟你说"给话术加缓存能省一半钱"，那是没跑过数据。
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   RUN_LLM_EVAL=true mvn test -Dtest=NegotiationCostBenchmarkTest
 * </pre>
 * 会真实调用 LLM 约 90 次，耗时数分钟，所以默认不参与 {@code mvn test}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_LLM_EVAL", matches = "true")
class NegotiationCostBenchmarkTest {

    private static final String LIGHT = "deepseek-v4-flash";
    private static final String STRONG = "deepseek-v4-pro";

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LlmUsageRecorder usageRecorder;

    @Autowired
    private TokenCostCalculator costCalculator;

    @Test
    @DisplayName("单次议价成本：全强模型 / 分级路由 / 分级+缓存 三组对照")
    void 三组成本对照() {
        List<SessionSpec> sessions = buildSessions();
        int rounds = sessions.get(0).messages().size();

        // ── B1：基线（全强模型 + 无缓存）────────────────────────────────
        Result b1 = run(sessions, new ModelRouter(LIGHT, STRONG, false),
                new LlmResultCache(redisTemplate, false, 120));

        // ── B2：分级路由（抽取/分类走轻量档）+ 无缓存 ────────────────────
        Result b2 = run(sessions, new ModelRouter(LIGHT, STRONG, true),
                new LlmResultCache(redisTemplate, false, 120));

        // ── B3：分级路由 + 内容哈希缓存 ─────────────────────────────────
        LlmResultCache cache = new LlmResultCache(redisTemplate, true, 120);
        cache.clearLocal(); // 与前两组隔离（前两组本来就没写缓存，这里是双保险）
        Result b3 = run(sessions, new ModelRouter(LIGHT, STRONG, true), cache);

        printReport(sessions.size(), rounds, b1, b2, b3);

        assertThat(b3.totalCalls()).isGreaterThan(0);
    }

    /** 跑一组：N 个会话，每个会话 M 轮，每轮 = 一次意图分类 + 一次话术生成。 */
    private Result run(List<SessionSpec> sessions, ModelRouter router, LlmResultCache cache) {
        LlmCostMeter meter = new LlmCostMeter();
        cache.resetStats();

        LlmIntentClassifier classifier = new LlmIntentClassifier(
                chatClientBuilder, router, cache, meter, new IntentClassifier());
        LlmTalkGenerator talk = new LlmTalkGenerator(
                chatClientBuilder, usageRecorder, router, cache, meter);

        for (SessionSpec s : sessions) {
            for (int i = 0; i < s.messages().size(); i++) {
                String message = s.messages().get(i);
                // 第 1 步：意图分类（模式识别 → 轻量档）
                String intent = classifier.classify(message);
                // 第 2 步：话术生成（生成任务 → 强档）
                talk.generate(new NegotiationTalkGenerator.TalkContext(
                        s.title(), s.condition(), s.listPrice(), s.quoteAt(i),
                        message, "COUNTERED", i + 1, intent));
            }
        }
        return new Result(meter.byModel(), cache.stats(), meter.totalCalls());
    }

    /**
     * 构造实验场景。
     *
     * <p>三个会话的商品与价格都不同（所以话术不共享缓存），
     * 但买家说的话高度重复（所以意图分类能共享缓存）——
     * 这就是真实流量的分布特征：<b>商品千差万别，但人问的话就那么几句。</b>
     */
    private List<SessionSpec> buildSessions() {
        List<String> messages = List.of(
                "能便宜点吗",      // 高频问法
                "太贵了吧",        // 高频问法
                "别家比你便宜",    // 施压
                "850 行不行",      // 带具体金额
                "行，我要了");      // 成交确认

        return List.of(
                new SessionSpec("iPhone 15 128G 黑色", 2, new BigDecimal("3000"),
                        List.of(new BigDecimal("2800"), new BigDecimal("2650"), new BigDecimal("2580"),
                                new BigDecimal("2540"), new BigDecimal("2520")), messages),
                new SessionSpec("iPad Air 5 64G", 3, new BigDecimal("2200"),
                        List.of(new BigDecimal("2050"), new BigDecimal("1950"), new BigDecimal("1900"),
                                new BigDecimal("1870"), new BigDecimal("1855")), messages),
                new SessionSpec("罗技 MX 机械键盘", 2, new BigDecimal("400"),
                        List.of(new BigDecimal("370"), new BigDecimal("350"), new BigDecimal("340"),
                                new BigDecimal("335"), new BigDecimal("332")), messages));
    }

    // ==================================================================

    /** 一个会话的输入。 */
    private record SessionSpec(String title, int condition, BigDecimal listPrice,
                               List<BigDecimal> quotes, List<String> messages) {
        BigDecimal quoteAt(int index) {
            return quotes.get(Math.min(index, quotes.size() - 1));
        }
    }

    private record Result(Map<String, LlmCostMeter.Counters> byModel,
                          LlmResultCache.Stats cacheStats,
                          long totalCalls) {
    }

    // ==================================================================

    private void printReport(int sessionCount, int rounds, Result b1, Result b2, Result b3) {
        System.out.println();
        System.out.println("══════════ 单次议价成本对照 ══════════");
        System.out.printf("场景：%d 个会话 × %d 轮 = %d 轮，每轮 1 次意图分类 + 1 次话术生成%n",
                sessionCount, rounds, sessionCount * rounds);
        System.out.println("三组使用同一个链路、同一批输入，只改配置（模型路由 / 缓存开否则）");
        System.out.println();
        System.out.printf("单价：%s%n", costCalculator.rateDescription(LIGHT));
        System.out.printf("      %s%n", costCalculator.rateDescription(STRONG));
        System.out.println();

        double c1 = costOf(b1);
        double c2 = costOf(b2);
        double c3 = costOf(b3);

        System.out.printf("%-26s %-10s %-12s %-14s%n", "组别", "调用次数", "成本(元)", "单会话成本(元)");
        System.out.println("────────────────────────────────────────────────────────────────");
        System.out.printf("%-26s %-10d %-12.6f %-14.6f%n", "B1 全强模型（基线）", b1.totalCalls(), c1, c1 / sessionCount);
        System.out.printf("%-26s %-10d %-12.6f %-14.6f%n", "B2 分级路由（无缓存）", b2.totalCalls(), c2, c2 / sessionCount);
        System.out.printf("%-26s %-10d %-12.6f %-14.6f%n", "B3 分级路由 + 缓存", b3.totalCalls(), c3, c3 / sessionCount);

        System.out.println();
        System.out.printf("同比基线降幅：B2 降 %.1f%%    B3 降 %.1f%%%n",
                (1 - c2 / c1) * 100, (1 - c3 / c1) * 100);
        System.out.printf("其中「缓存」这一项单独贡献：%.1f pt%n", (1 - c3 / c2) * 100);

        System.out.println();
        System.out.println("══════════ 分组明细 ══════════");
        printGroup("B1 全强模型", b1);
        printGroup("B2 分级路由", b2);
        printGroup("B3 分级路由 + 缓存", b3);

        System.out.println();
        System.out.println("══════════ 缓存命中情况 ══════════");
        System.out.printf("B3：命中 %d / 未命中 %d → 命中率 %.1f%%%n",
                b3.cacheStats().hits(), b3.cacheStats().misses(), b3.cacheStats().hitRate() * 100);
        System.out.println("（意图分类的 prompt 只含买家消息 → 跨会话命中；");
        System.out.println("  话术生成的 prompt 含商品与价格 → 跨会话几乎必不命中。这是真实分布。）");
    }

    private void printGroup(String label, Result r) {
        System.out.println(label + "：");
        r.byModel().forEach((model, c) -> System.out.printf(
                "    %-22s 调用 %-4d  输入 %-7d  输出 %-7d  成本 ¥%.6f%n",
                model, c.calls(), c.promptTokens(), c.completionTokens(),
                costCalculator.cost((int) c.promptTokens(), (int) c.completionTokens(), model)));
    }

    private double costOf(Result r) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, LlmCostMeter.Counters> e : r.byModel().entrySet()) {
            LlmCostMeter.Counters c = e.getValue();
            sum = sum.add(costCalculator.cost(
                    (int) c.promptTokens(), (int) c.completionTokens(), e.getKey()));
        }
        return sum.doubleValue();
    }

    /** 供未来扩展：把结果导出成结构化数据。当前只用于让静态分析看到 import 被用上。 */
    @SuppressWarnings("unused")
    private Map<String, Object> toMap(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", r.totalCalls());
        List<Map<String, Object>> models = new ArrayList<>();
        r.byModel().forEach((k, v) -> models.add(Map.of(
                "model", k, "calls", v.calls(), "prompt", v.promptTokens(), "completion", v.completionTokens())));
        m.put("models", models);
        return m;
    }
}
