package com.example.unitrade.search;

import com.example.unitrade.llm.LlmCostMeter;
import com.example.unitrade.llm.LlmResultCache;
import com.example.unitrade.llm.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数抽取的字段级准确率评测 —— 同时跑轻量档与强档，用于回答一个具体问题：
 * <b>"这一步走轻量模型，到底省了多少、代价是多少准确率？"</b>
 *
 * <h2>为什么要两档对照，而不是只跑一档</h2>
 * 只说"准确率 91%"是没有信息量的：读者无法判断这是好是坏。
 * 但把它和"用强模型的 95%"放在一起，决策依据就出来了 ——
 * 如果轻量档只低 2 个点却省一半钱，那就该用轻量档；
 * 如果低 20 个点，那这个环节根本不该走轻量档。
 * <b>分级路由不是"能省就省"，而是"用数据决定哪一步可以省"。</b>
 *
 * <h2>怎么跑</h2>
 * 它会真实调用 LLM（80 次左右），所以默认<b>不参与</b> {@code mvn test}：
 * <pre>
 *   RUN_LLM_EVAL=true mvn test -Dtest=ParamExtractionEvalTest
 * </pre>
 * 打开开关才跑，是为了让日常回归测试保持"零成本、可离线、几秒钟"。
 *
 * <p>⚠️ {@code webEnvironment = RANDOM_PORT} 不是可选项：
 * 应用上下文里的 WebSocket 配置需要一个真实的 {@code ServerContainer}，
 * 在默认的 MOCK 环境下会以
 * {@code jakarta.websocket.server.ServerContainer not available} 启动失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_LLM_EVAL", matches = "true")
class ParamExtractionEvalTest {

    /** 与 application.yml 的默认值保持一致，测试里显式传入以便对照。 */
    private static final String LIGHT = "deepseek-v4-flash";
    private static final String STRONG = "deepseek-v4-pro";

    /** 参与评分的 4 个字段 —— 与 ProductQueryDTO 里检索真正会用到的字段一一对应。 */
    private static final List<String> FIELDS = List.of("keyword", "minPrice", "maxPrice", "category");

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private LlmResultCache cache;

    @Autowired
    private LlmCostMeter costMeter;

    @Test
    @DisplayName("参数抽取：轻量档 vs 强档 的字段级准确率与成本对照")
    void 字段级准确率对照() throws IOException {
        List<Case> cases = loadCases();
        assertThat(cases).as("评测集不能为空").isNotEmpty();

        // ── 第一组：分级路由（抽取走轻量档）─────────────────────────────
        cache.resetStats();
        costMeter.reset();
        ModelRouter routed = new ModelRouter(LIGHT, STRONG, true);
        Report lightReport = evaluate(
                new QueryParamExtractor(chatClientBuilder, routed, cache, costMeter), cases);
        LlmResultCache.Stats lightCache = cache.stats();
        Map<String, LlmCostMeter.Counters> lightUsage = costMeter.byModel();

        // ── 第二组：基线（routing-enabled=false，全部走强档）─────────────
        //    注意缓存不会串：指纹里带了模型名，两个档位的 key 天然不同
        cache.resetStats();
        costMeter.reset();
        ModelRouter baseline = new ModelRouter(LIGHT, STRONG, false);
        Report strongReport = evaluate(
                new QueryParamExtractor(chatClientBuilder, baseline, cache, costMeter), cases);
        Map<String, LlmCostMeter.Counters> strongUsage = costMeter.byModel();

        printReport(cases.size(), lightReport, lightCache, lightUsage, strongReport, strongUsage);

        // 只断言"跑通了"，不断言具体准确率 ——
        // 准确率是待测的观测值，把它写成断言会让测试变成"记录当前表现"，
        // 改了 prompt 就得改测试，反而没人敢改。
        assertThat(lightReport.totalFields()).isEqualTo(cases.size() * FIELDS.size());
        assertThat(strongReport.totalFields()).isEqualTo(cases.size() * FIELDS.size());
    }

    // ==================================================================

    /** 跑一遍评测。 */
    private Report evaluate(QueryParamExtractor extractor, List<Case> cases) {
        int total = 0;
        int correct = 0;
        int parseFailures = 0;
        Map<String, int[]> perField = new LinkedHashMap<>();
        for (String f : FIELDS) {
            perField.put(f, new int[]{0, 0}); // [正确, 总数]
        }
        List<String> errors = new ArrayList<>();

        for (Case c : cases) {
            QueryParamExtractor.Extraction e = extractor.extract(c.query);
            if (e.parseFailed()) {
                parseFailures++;
            }

            boolean ok = true;
            for (String field : FIELDS) {
                boolean hit = match(field, e, c.expected());
                perField.get(field)[1]++;
                total++;
                if (hit) {
                    correct++;
                    perField.get(field)[0]++;
                } else {
                    ok = false;
                }
            }
            if (!ok) {
                errors.add("%s | %s%n      期望=%s%n      实际=%s".formatted(
                        c.id(), c.query(), c.expected(), actualOf(e)));
            }
        }
        return new Report(total, correct, parseFailures, perField, errors);
    }

    /**
     * 单字段比对。
     *
     * <p>keyword 用<b>归一化后精确相等</b>，不做"包含即算对"的放宽 ——
     * 放宽会让准确率虚高，而虚高的数字在面试里撑不住一句"你怎么判定的"。
     * 归一化只处理纯书写差异（大小写、空格），这本来就不该算错。
     */
    private boolean match(String field, QueryParamExtractor.Extraction e, Expected exp) {
        return switch (field) {
            case "keyword" -> eq(e.keyword(), exp.keyword());
            case "minPrice" -> numEq(e.minPrice(), exp.minPrice());
            case "maxPrice" -> numEq(e.maxPrice(), exp.maxPrice());
            case "category" -> eq(e.category(), exp.category());
            default -> false;
        };
    }

    private boolean eq(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank();
        }
        return b != null && a.equals(b.trim());
    }

    private boolean numEq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    private String actualOf(QueryParamExtractor.Extraction e) {
        return "{keyword=%s, minPrice=%s, maxPrice=%s, category=%s}%s".formatted(
                e.keyword(), e.minPrice(), e.maxPrice(), e.category(),
                e.parseFailed() ? " [解析失败]" : "");
    }

    /** 读取评测集。 */
    private List<Case> loadCases() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Case> cases = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/eval/query_param_cases.jsonl")) {
            if (in == null) {
                throw new IOException("找不到评测集 /eval/query_param_cases.jsonl");
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                JsonNode exp = node.get("expect");
                cases.add(new Case(
                        node.get("id").asText(),
                        node.get("query").asText(),
                        new Expected(
                                textOrNull(exp, "keyword"),
                                decOrNull(exp, "minPrice"),
                                decOrNull(exp, "maxPrice"),
                                textOrNull(exp, "category"))));
            }
        }
        return cases;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private BigDecimal decOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : new BigDecimal(v.asText());
    }

    // ==================================================================

    private void printReport(int caseCount,
                             Report light, LlmResultCache.Stats lightCache,
                             Map<String, LlmCostMeter.Counters> lightUsage,
                             Report strong, Map<String, LlmCostMeter.Counters> strongUsage) {
        System.out.println();
        System.out.println("══════════ 参数抽取评测 · 字段级准确率 ══════════");
        System.out.printf("样本 %d 条 × %d 个字段 = %d 个字段判定%n",
                caseCount, FIELDS.size(), light.totalFields());
        System.out.printf("评测集：src/test/resources/eval/query_param_cases.jsonl%n");
        System.out.println();
        System.out.printf("%-12s %-14s %-14s %-10s%n", "字段", "轻量档", "强档", "差值");
        for (String f : FIELDS) {
            double l = pct(light.perField().get(f));
            double s = pct(strong.perField().get(f));
            System.out.printf("%-12s %-14s %-14s %s%n",
                    f, fmt(l), fmt(s), delta(l, s));
        }
        System.out.println("────────────────────────────────────────────────");
        double lAll = pct(light.correct(), light.totalFields());
        double sAll = pct(strong.correct(), strong.totalFields());
        System.out.printf("%-12s %-14s %-14s %s%n", "字段级总计", fmt(lAll), fmt(sAll), delta(lAll, sAll));
        System.out.println();
        System.out.printf("解析失败：轻量档 %d 条 / 强档 %d 条%n", light.parseFailures(), strong.parseFailures());
        System.out.printf("缓存：命中 %d / 未命中 %d（命中率 %.1f%%）—— 强档那轮指纹含模型名，不会串用轻量档的结果%n",
                lightCache.hits(), lightCache.misses(), lightCache.hitRate() * 100);

        System.out.println();
        System.out.println("══════════ token 消耗对照 ══════════");
        printUsage("分级路由（抽取走轻量档）", lightUsage);
        printUsage("基线（全部强档）", strongUsage);

        if (!light.errors().isEmpty()) {
            System.out.println();
            System.out.println("══════════ 轻量档抽错的字段明细（前 12 条）══════════");
            light.errors().stream().limit(12).forEach(e -> System.out.println("  " + e));
        }
    }

    private void printUsage(String label, Map<String, LlmCostMeter.Counters> usage) {
        System.out.println(label + "：");
        usage.forEach((model, c) -> System.out.printf(
                "    %-22s 调用 %-4d 次  输入 %-8d  输出 %-8d  合计 %d tokens%n",
                model, c.calls(), c.promptTokens(), c.completionTokens(), c.totalTokens()));
    }

    private double pct(int[] correctAndTotal) {
        return correctAndTotal[1] == 0 ? 0.0 : correctAndTotal[0] * 100.0 / correctAndTotal[1];
    }

    private double pct(int correct, int total) {
        return total == 0 ? 0.0 : correct * 100.0 / total;
    }

    private String fmt(double pct) {
        return "%.1f%%".formatted(pct);
    }

    private String delta(double a, double b) {
        double d = a - b;
        return (d >= 0 ? "+" : "") + "%.1f pt".formatted(d);
    }

    // ==================================================================

    private record Case(String id, String query, Expected expected) {
    }

    private record Expected(String keyword, BigDecimal minPrice, BigDecimal maxPrice, String category) {
    }

    private record Report(int totalFields, int correct, int parseFailures,
                          Map<String, int[]> perField, List<String> errors) {
    }
}
