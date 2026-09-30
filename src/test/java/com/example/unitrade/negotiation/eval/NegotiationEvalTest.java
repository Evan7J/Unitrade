package com.example.unitrade.negotiation.eval;

import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;
import com.example.unitrade.negotiation.graph.IntentClassifier;
import com.example.unitrade.negotiation.pricing.AuthorizationGate;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PricingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 议价 Agent 评测 —— 让"效果"和"安全"变成可复现的数字。
 *
 * <h2>评测分四级，这里跑的是 L2（效果）与 L3（安全）</h2>
 * <table border="1">
 *   <caption>四级评测</caption>
 *   <tr><th>级别</th><th>测什么</th><th>在哪</th></tr>
 *   <tr><td>L1 数学正确性</td><td>报价单调、不击穿底价、锚定 40%</td><td>negotiation-kernel 的 39 个测试</td></tr>
 *   <tr><td><b>L2 效果</b></td><td>意图识别 Macro-F1、混淆矩阵</td><td><b>本类</b></td></tr>
 *   <tr><td><b>L3 安全</b></td><td>越界拦截数 / 误放数 / 误拦数</td><td><b>本类</b></td></tr>
 *   <tr><td>L4 经济性</td><td>token 成本、模型路由对比</td><td>本类（结构预留，成本需真实调用日志）</td></tr>
 * </table>
 *
 * <h2>为什么"误放必须为 0"要写成断言而不是指标</h2>
 * 拦截率是<b>效果指标</b>——80% 意味着"漏了一些"，可以迭代改进；
 * 误放是<b>安全指标</b>——放行一次就意味着有一单真的低于卖家底价成交了。
 * 两者的容错度根本不同，所以一个用来观察，一个用来卡关。
 *
 * <h2>⚠️ 关于数字口径（面试必问）</h2>
 * <ul>
 *   <li>样本量：<b>意图 90 条 + 对抗 40 条 = 130 条</b>，全部放在
 *       {@code src/test/resources/eval/*.jsonl} 里，进版本库、可追溯。
 *       这里就写真实数量，不凑"200 条"这种整数 —— 凑数字骗不了追问；</li>
 *   <li>"误放"的<b>分母是"构成越界的样本数"，不是样本总数</b>；
 *       "拦截次数"按<b>消息条数</b>统计，会话去重后的数字会明显更小；</li>
 *   <li>意图识别用的是<b>规则分类器</b>，所以这里的 Macro-F1 是规则版的实测值，
 *       不是"上模型后"的预期值 —— <b>报预期值就是编数字</b>。</li>
 * </ul>
 */
class NegotiationEvalTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PricingEngine engine = BuiltInStrategies.defaultEngine();
    private final AuthorizationGate gate = new AuthorizationGate();
    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    @DisplayName("L2 意图识别：混淆矩阵 + Macro-F1 / Micro-F1")
    void intentEvaluation() throws Exception {
        List<IntentCase> cases = loadIntentCases();
        assertThat(cases).as("评测集不能为空").isNotEmpty();

        // 混淆矩阵：expected -> (actual -> count)
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        List<String> labels = new ArrayList<>();

        for (IntentCase c : cases) {
            String actual = classifier.classify(c.message());
            String expected = c.intent();
            if (!labels.contains(expected)) {
                labels.add(expected);
            }
            matrix.computeIfAbsent(expected, k -> new TreeMap<>())
                    .merge(actual, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== L2 意图识别评测 ==========\n");
        sb.append("样本量: ").append(cases.size()).append(" 条\n\n");

        int correct = 0;
        double macroF1Sum = 0;
        int labelCount = 0;
        List<String> weakClasses = new ArrayList<>();

        sb.append(String.format("%-14s %9s %9s %9s %8s%n",
                "意图", "Precision", "Recall", "F1", "支持数"));
        for (String label : labels) {
            int tp = matrix.getOrDefault(label, Map.of()).getOrDefault(label, 0);
            int support = matrix.getOrDefault(label, Map.of()).values().stream()
                    .mapToInt(Integer::intValue).sum();
            int predicted = 0;
            for (Map<String, Integer> row : matrix.values()) {
                predicted += row.getOrDefault(label, 0);
            }

            double precision = predicted == 0 ? 0 : (double) tp / predicted;
            double recall = support == 0 ? 0 : (double) tp / support;
            double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

            macroF1Sum += f1;
            labelCount++;
            correct += tp;

            sb.append(String.format("%-14s %8.1f%% %8.1f%% %8.1f%% %8d%n",
                    label, precision * 100, recall * 100, f1 * 100, support));

            // 少数类表现单独点名：Macro-F1 的意义就在这里
            if (f1 < 0.6) {
                weakClasses.add(String.format("%s(F1=%.1f%%, 支持数=%d)", label, f1 * 100, support));
            }
        }

        double macroF1 = labelCount == 0 ? 0 : macroF1Sum / labelCount;
        double microF1 = (double) correct / cases.size();

        sb.append(String.format("%nMacro-F1 = %.1f%%   （各类 F1 的算术平均，少数类权重与多数类相同）%n",
                macroF1 * 100));
        sb.append(String.format("Micro-F1 = %.1f%%   （即准确率，被多数类主导）%n", microF1 * 100));
        sb.append(String.format("两者差距 = %.1f 个百分点%n", (microF1 - macroF1) * 100));
        if (!weakClasses.isEmpty()) {
            sb.append("短板类别（F1 < 60%）: ").append(String.join(", ", weakClasses)).append("\n");
            sb.append("→ 这些类别样本少但最不能错：判错会走到错误分支（如把 PROBE_FLOOR 当成闲聊）\n");
        }

        System.out.println(sb);
        writeReport("L2-intent", sb.toString());

        // 断言：规则分类器至少要比"全猜多数类"好得多，否则说明规则完全失效
        assertThat(microF1).as("意图识别准确率过低，规则需要检查").isGreaterThan(0.5);
    }

    @Test
    @DisplayName("L3 安全：越界拦截 / 误放（必须为 0）/ 误拦")
    void guardEvaluation() throws Exception {
        List<AdvCase> cases = loadAdversarialCases();
        assertThat(cases).isNotEmpty();

        int violationSamples = 0;   // 构成越界的样本数（误放率的分母）
        int blocked = 0;            // 拦截（正确识别越界并挂起）
        int missed = 0;             // 误放（该拦没拦）
        int overBlocked = 0;        // 误拦（不该拦却拦了）
        List<String> missedSamples = new ArrayList<>();

        for (AdvCase c : cases) {
            AuthorizedRange range = new AuthorizedRange(
                    Money.ofYuan(c.listPrice()), Money.ofYuan(c.floorPrice()), 0);
            // 用第 3 轮报价做校验（模拟谈判进行到中段）
            Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, 3);

            boolean belowFloor = Money.ofYuan(c.offer()).isLessThan(range.floorPrice());
            if (belowFloor) {
                violationSamples++;
            }

            BargainOutcome outcome = gate.checkBuyerOffer(Money.ofYuan(c.offer()), range, quote);
            boolean suspended = outcome instanceof BargainOutcome.Suspended;

            if (belowFloor && suspended) {
                blocked++;
            } else if (belowFloor) {
                missed++;
                missedSamples.add(c.id());
            } else if (suspended) {
                overBlocked++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== L3 风控评测（对抗回放）==========\n");
        sb.append("对抗样本总数        = ").append(cases.size()).append(" 条\n");
        sb.append("其中构成越界的样本   = ").append(violationSamples).append(" 条\n");
        sb.append("拦截                = ").append(blocked).append(" 次\n");
        sb.append("误放（该拦没拦）     = ").append(missed).append(" 次\n");
        sb.append("误拦（正常被拦）     = ").append(overBlocked).append(" 次\n");
        if (violationSamples > 0) {
            sb.append(String.format("拦截率（分母=越界样本） = %.1f%%%n",
                    blocked * 100.0 / violationSamples));
            sb.append(String.format("误放率（分母=越界样本） = %.1f%%%n",
                    missed * 100.0 / violationSamples));
        }
        if (!missedSamples.isEmpty()) {
            sb.append("⚠️ 漏放的样本: ").append(String.join(", ", missedSamples)).append("\n");
        }
        sb.append("\n口径提醒：\n");
        sb.append("  · 「误放率」的分母是越界样本数(").append(violationSamples)
                .append(")，不是样本总数(").append(cases.size()).append(")\n");
        sb.append("  · 「拦截 N 次」按消息条数计；若按会话去重，数字会更小\n");
        sb.append("  · 只有「正好等于底价」不算越界 —— 这条边界来自闸门实现，改它等于改口径\n");

        System.out.println(sb);
        writeReport("L3-guard", sb.toString());

        // ★ 硬指标：一次都不能放
        assertThat(missed)
                .as("出现了误放！放行一次就意味着有一单会低于卖家底价成交")
                .isZero();
        assertThat(overBlocked)
                .as("出现了误拦：正常的出价被拦下会白白流失订单")
                .isZero();
    }

    // ==================================================================

    private record IntentCase(String id, String message, String intent) {
    }

    private record AdvCase(String id, BigDecimal listPrice, BigDecimal floorPrice,
                           BigDecimal offer, boolean expectSuspend) {
    }

    private List<IntentCase> loadIntentCases() throws Exception {
        List<IntentCase> list = new ArrayList<>();
        for (JsonNode n : readJsonl("eval/intent_cases.jsonl")) {
            list.add(new IntentCase(n.get("id").asText(), n.get("message").asText(),
                    n.get("intent").asText()));
        }
        return list;
    }

    private List<AdvCase> loadAdversarialCases() throws Exception {
        List<AdvCase> list = new ArrayList<>();
        for (JsonNode n : readJsonl("eval/adversarial_cases.jsonl")) {
            list.add(new AdvCase(
                    n.get("id").asText(),
                    new BigDecimal(n.get("listPrice").asText()),
                    new BigDecimal(n.get("floorPrice").asText()),
                    new BigDecimal(n.get("offer").asText()),
                    n.get("expectSuspend").asBoolean()));
        }
        return list;
    }

    private List<JsonNode> readJsonl(String resourcePath) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("评测集不存在: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        nodes.add(mapper.readTree(trimmed));
                    }
                }
            }
        }
        return nodes;
    }

    /** 把报告写进 target/，方便 CI 收集与人工查看。 */
    private void writeReport(String name, String content) throws Exception {
        Path dir = Path.of("target", "eval-reports");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".txt"), content, StandardCharsets.UTF_8);
    }
}
