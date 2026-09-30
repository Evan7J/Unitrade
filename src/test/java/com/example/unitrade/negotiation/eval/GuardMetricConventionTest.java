package com.example.unitrade.negotiation.eval;

import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;
import com.example.unitrade.negotiation.pricing.AuthorizationGate;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PricingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「拦截 N 次 / 误放 0 次」这个数字<b>是怎么数出来的</b>——把每一条样本都摆在明面上。
 *
 * <h2>为什么单独写这个</h2>
 * 「拦截 33 次、误放 0 次」这句话本身没有信息量，因为**同一个事实可以报出四种不同的数**，
 * 差别全在<b>分母</b>和<b>边界定义</b>上。把口径讲清楚比把数字讲大重要得多 ——
 * 面试官只要问一句"你分母是什么"，口径含糊的人立刻露。
 *
 * <p>本测试做三件事：
 * <ol>
 *   <li><b>逐条列出</b> 70 条对抗样本各自的期望与实际，标出是否命中；</li>
 *   <li><b>同一个 33 用四种分母各报一次</b>，让人看清"变的从来不是分子"；</li>
 *   <li><b>边界敏感度</b>：把"正好等于底价"从"不算越界"改成"算越界"，
 *       越界样本数会从 33 跳到 57 —— 改一个字，指标差 73%。</li>
 * </ol>
 */
class GuardMetricConventionTest {

    /** 与生产一致：用审计闸门的默认实现。 */
    private final AuthorizationGate gate = new AuthorizationGate();
    private final PricingEngine engine = BuiltInStrategies.defaultEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 一条对抗样本。字段名与 jsonl 的 key 一一对应。 */
    record Sample(String id, BigDecimal listPrice, BigDecimal floorPrice,
                  BigDecimal offer, boolean expectSuspend, String note) {
    }

    private List<Sample> loadSamples() throws Exception {
        List<Sample> samples = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/eval/adversarial_cases.jsonl")) {
            assertThat(in).as("评测集文件必须存在").isNotNull();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        samples.add(mapper.readValue(line, Sample.class));
                    }
                }
            }
        }
        return samples;
    }

    /** 判一条样本，返回闸门是否把它判为越界（挂起）。 */
    private boolean actuallySuspended(Sample s) {
        AuthorizedRange range = new AuthorizedRange(
                Money.ofYuan(s.listPrice()), Money.ofYuan(s.floorPrice()), 0);
        Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, 1);
        BargainOutcome outcome = gate.checkBuyerOffer(Money.ofYuan(s.offer()), range, quote);
        return outcome instanceof BargainOutcome.Suspended;
    }

    private static String money(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    @Test
    @DisplayName("① 逐条摆开：70 条对抗样本，每条判了什么、对不对")
    void 逐条明细() throws Exception {
        List<Sample> samples = loadSamples();
        int violation = 0;
        int blocked = 0;
        int missed = 0;
        int falseAlarm = 0;

        System.out.println();
        System.out.println("════════ 70 条对抗样本逐条明细 ════════");
        System.out.printf("%-6s %-10s %-10s %-10s %-14s %-6s%n",
                "编号", "挂牌价", "底价", "买家出价", "出价 vs 底价", "结果");
        System.out.println("─".repeat(78));

        for (Sample s : samples) {
            boolean suspended = actuallySuspended(s);
            int cmp = s.offer().compareTo(s.floorPrice());
            String relation = cmp < 0 ? "低于底价" : (cmp == 0 ? "正好等于" : "高于底价");

            String mark;
            if (s.expectSuspend()) {
                violation++;
                if (suspended) {
                    blocked++;
                    mark = "拦下 ✔";
                } else {
                    missed++;
                    mark = "误放 ✘";
                }
            } else {
                mark = suspended ? "误拦 ✘" : "放行 ✔";
            }
            if (!s.expectSuspend() && suspended) {
                falseAlarm++;
            }

            System.out.printf("%-6s %-10s %-10s %-10s %-14s %-6s%n",
                    s.id(), money(s.listPrice()), money(s.floorPrice()),
                    money(s.offer()), relation, mark);
        }

        System.out.println("─".repeat(78));
        System.out.printf("对抗样本总数        = %d 条（其中有 %d 条低于底价 → 只有这些才算「越界样本」）%n",
                samples.size(), violation);
        System.out.printf("拦下                = %d%n", blocked);
        System.out.printf("误放（该拦没拦）    = %d%n", missed);
        System.out.printf("误拦（正常被拦）    = %d%n", falseAlarm);
        System.out.println();

        assertThat(violation).as("设计上应有 33 条越界样本").isEqualTo(33);
        assertThat(missed).as("误放必须为 0 —— 这是安全指标，不是效果指标").isZero();
        assertThat(falseAlarm).as("误拦必须为 0").isZero();
    }

    @Test
    @DisplayName("② 同一个 33，四种分母 —— 变的一直是分母，不是分子")
    void 四种口径对比() throws Exception {
        int violation = 0;
        int blocked = 0;
        for (Sample s : loadSamples()) {
            if (s.expectSuspend()) {
                violation++;
                if (actuallySuspended(s)) {
                    blocked++;
                }
            }
        }
        int totalSamples = loadSamples().size();
        int fullEvalSet = 200;   // intent 130 + adversarial 70

        System.out.println();
        System.out.println("════════ 同一个事实的四种说法 ════════");
        System.out.printf("%-38s %-16s %-10s%n", "说法", "算式", "结果");
        System.out.println("─".repeat(70));
        print("「该拦的都拦住了」拦截率", blocked + "/" + violation, pct(blocked, violation));
        print("「对抗样本里的拦截率」", blocked + "/" + totalSamples, pct(blocked, totalSamples));
        print("「整个评测集上的拦截率」", blocked + "/" + fullEvalSet, pct(blocked, fullEvalSet));
        print("「误放率」", "0/" + violation + " 或 0/" + totalSamples, "0%");
        System.out.println("─".repeat(70));
        System.out.println("分子永远是 33。上面前三行说的是同一件事，差别只在**你拿什么当分母**。");
        System.out.println();
        System.out.println("所以报数字时必须连口径一起报：");
        System.out.println("  「70 条对抗样本里有 33 条构成越界，全部拦下、误放 0」");
        System.out.println("   —— 这句话没有任何歧义，面试官追问也问不倒。");
        System.out.println();

        assertThat(blocked).isEqualTo(33);
    }

    @Test
    @DisplayName("③ 边界敏感度：把「等于底价」也算越界，数字会从 33 变多少")
    void 边界一改数字就变() throws Exception {
        List<Sample> samples = loadSamples();
        int strictLess = 0;         // 当前定义：出价 < 底价 才算越界
        int lessOrEqual = 0;        // 另一种定义：出价 ≤ 底价 也算越界

        for (Sample s : samples) {
            int cmp = s.offer().compareTo(s.floorPrice());
            if (cmp < 0) {
                strictLess++;
                lessOrEqual++;
            } else if (cmp == 0) {
                lessOrEqual++;
            }
        }

        System.out.println();
        System.out.println("════════ 边界定义改一个字，指标差多少 ════════");
        System.out.printf("当前定义「出价 < 底价 才算越界」  → 越界样本 %d 条%n", strictLess);
        System.out.printf("另一种「出价 ≤ 底价 也算越界」    → 越界样本 %d 条%n", lessOrEqual);
        System.out.printf("差 %d 条，相对涨幅 %.0f%%%n",
                lessOrEqual - strictLess, (lessOrEqual - strictLess) * 100.0 / strictLess);
        System.out.println();
        System.out.printf("差的这 %d 条，全部是「出价正好等于底价」的样本 —— 一条都不多、一条不少。%n",
                lessOrEqual - strictLess);
        System.out.println("两种定义都能讲通 —— 但**指标会因此完全不同**，");
        System.out.println("所以说「拦截率 100%」的时候，必须同时说明越界是怎么定义的。");
        System.out.println();

        assertThat(strictLess).isEqualTo(33);
        assertThat(lessOrEqual).isGreaterThan(strictLess);
    }

    private static void print(String label, String formula, String result) {
        System.out.printf("%-38s %-16s %-10s%n", label, formula, result);
    }

    private static String pct(int numerator, int denominator) {
        return String.format("%.1f%%", numerator * 100.0 / denominator);
    }
}
