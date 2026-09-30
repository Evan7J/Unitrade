package com.example.unitrade.negotiation.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import com.example.unitrade.negotiation.talk.LlmUsageRecorder;
import com.example.unitrade.negotiation.talk.NegotiationTalkGenerator;
import com.example.unitrade.negotiation.talk.TemplateTalkGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图 → 动作 是否真的接通了。
 *
 * <h2>为什么必须专门有这么一个测试</h2>
 * 这套代码曾经有一个"看起来在工作"的缺陷：意图节点把 {@code intent} 写进了 state，
 * 但<b>没有任何地方读它</b> —— 条件边只看 {@code branch}（由闸门产生）。
 * 后果是 9 类意图里只有"出价"这一类真正驱动了分支，其余全是装饰：
 * <ul>
 *   <li>「你底价多少」不会硬拒答，只靠 prompt 里一句"别提底价"兜着；</li>
 *   <li>「我要投诉」<b>不会转人工</b>；</li>
 *   <li>「行，我要了」不会出成交判断。</li>
 * </ul>
 *
 * <p>这类缺陷的危险之处在于<b>它不报错</b>：意图识别节点照样执行、
 * 照样有准确率指标（Macro-F1 91.2%），评测集照样全绿 ——
 * 因为评测的是"分类对不对"，而不是"分类有没有被用"。
 * 一个算了但不用的东西，指标再漂亮也不产生任何行为差异。
 *
 * <h2>这个测试断言的是什么</h2>
 * <ol>
 *   <li><b>接通</b>：每个意图都走到了它该去的出口（REFUSED / ESCALATED / ACCEPTED / COUNTERED ...）；</li>
 *   <li><b>风控优先</b>：带着越界出价的投诉消息，仍然判 SUSPENDED 并留痕 ——
 *       意图路由不能成为绕过价格校验的后门；</li>
 *   <li><b>数值不受意图影响</b>：拒绝套底价那一轮，报价一字未动。</li>
 * </ol>
 *
 * <p>它<b>不起 Spring、不连数据库、不调模型</b>：图本身是纯计算，
 * 所以这类"编排正确性"的验证可以在毫秒级完成 —— 这是"节点必须薄"的直接回报。
 */
class IntentRoutingTest {

    private static final long LIST_FEN = 100_000L;   // 挂牌 ¥1000
    private static final long FLOOR_FEN = 82_000L;   // 底价 ¥820
    private static final int DAYS_LISTED = 0;

    /** 与服务层同一个配置值（negotiation.max-rounds=5）。 */
    private static final int MAX_ROUNDS = 5;

    private final IntentClassifier classifier = new IntentClassifier();

    /**
     * 直接构造图 —— 不用 Spring 容器。
     * {@code negotiationGraph(...)} 就是 {@code @Bean} 方法本身，是公开的，
     * 这也是"图不依赖 Spring"的一个证据。
     *
     * <p>第二个参数 {@code null} 是模型版分类器：本测试用 rules 模式（{@code "rule"}），
     * 图内部会走规则版。传 null 而不是造一个假的，是为了让
     * "脱离 Spring 时图仍然可用"这件事本身也被测到。
     */
    private final CompiledGraph graph = new NegotiationGraphConfig(MAX_ROUNDS, "rule")
            .negotiationGraph(classifier, null, new TemplateTalkGenerator(), new LlmUsageRecorder());

    private final AuthorizedRange range =
            new AuthorizedRange(new Money(LIST_FEN), new Money(FLOOR_FEN), DAYS_LISTED);

    /** 首轮报价（无扰动的设计值）：pMax=0.8、a=0.4 → 让出 40% × 180 = ¥72 → ¥928 */
    private static final BigDecimal FIRST_QUOTE = BigDecimal.valueOf(92_800L, 2);

    /** 第 2 轮报价：p(2)=0.6 → 让出 ¥108 → ¥892。run() 固定以轮次 2 执行。 */
    private static final BigDecimal SECOND_QUOTE = BigDecimal.valueOf(89_200L, 2);

    // ==================================================================
    // 执行一次的脚手架
    // ==================================================================

    /**
     * 跑一轮图。
     *
     * <p>{@code threadId} 必须每个用例都不同：Checkpoint 按 threadId 存，
     * 复用同一个会让上一个用例的状态渗进来（这正是本项目踩过的那个坑）。
     */
    private OverAllState run(String threadId, String message, BigDecimal offerYuan) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(NegotiationGraphConfig.KEY_BUYER_MESSAGE, message);
        inputs.put(NegotiationGraphConfig.KEY_RANGE, range);
        inputs.put(NegotiationGraphConfig.KEY_STRATEGY, BuiltInStrategies.PRODUCTION_STRATEGY);
        inputs.put(NegotiationGraphConfig.KEY_ROUND, 2);
        // 模拟"已经报过首轮价"，拒绝套底价时要沿用它
        inputs.put(NegotiationGraphConfig.KEY_PREV_QUOTE, FIRST_QUOTE);
        inputs.put("listPrice", BigDecimal.valueOf(LIST_FEN, 2));
        inputs.put("productTitle", "E2E-意图路由测试");
        inputs.put("condition", 2);
        if (offerYuan != null) {
            inputs.put(NegotiationGraphConfig.KEY_OFFER, offerYuan);
        }
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        return graph.invoke(inputs, config).orElseThrow(() -> new IllegalStateException("图没有返回状态"));
    }

    private String branchOf(OverAllState state) {
        return state.value(NegotiationGraphConfig.KEY_BRANCH, "");
    }

    // ==================================================================
    // ① 接通：每个意图都走到该去的出口
    // ==================================================================

    @Test
    @DisplayName("套底价 → REFUSED（拒答，且话术里一个价格都没有）")
    void 套底价被拒答() {
        OverAllState state = run("t-probe-floor", "你底价多少啊，直接说个数", null);

        assertThat(branchOf(state)).isEqualTo("REFUSED");
        assertThat(state.value(NegotiationGraphConfig.KEY_INTENT, "")).isEqualTo("PROBE_FLOOR");

        String reply = state.value(NegotiationGraphConfig.KEY_REPLY, "");
        // 反向校验：这一类话术必须【不含任何价格】
        assertThat(NegotiationTalkGenerator.PRICE_IN_TALK.matcher(reply).find())
                .as("拒绝透露底价的话术里不该出现任何价格：%s", reply)
                .isFalse();
        // 报价沿用上一轮，不能因为被问一次就降价
        assertThat((BigDecimal) state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow())
                .isEqualByComparingTo(FIRST_QUOTE);

        System.out.printf("[接通] 套底价      → %-10s 报价=¥%s  话术=%s%n",
                branchOf(state), state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow(), reply);
    }

    @Test
    @DisplayName("情绪投诉 → ESCALATED（挂起转人工）")
    void 投诉触发转人工() {
        OverAllState state = run("t-complaint", "你这态度太差了，我要投诉你", null);

        assertThat(branchOf(state)).isEqualTo("ESCALATED");
        assertThat(state.value(NegotiationGraphConfig.KEY_SUSPEND_REASON, ""))
                .isEqualTo("INTENT_COMPLAINT");

        System.out.printf("[接通] 情绪投诉    → %-10s 原因=%s%n",
                branchOf(state), state.value(NegotiationGraphConfig.KEY_SUSPEND_REASON, ""));
    }

    @Test
    @DisplayName("确认成交（没报新价）→ ACCEPTED，按我方当前报价成交")
    void 确认成交直接接受() {
        OverAllState state = run("t-confirm", "行，我要了", null);

        assertThat(branchOf(state)).isEqualTo("ACCEPTED");
        assertThat((BigDecimal) state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow())
                .isEqualByComparingTo(SECOND_QUOTE);

        System.out.printf("[接通] 确认成交    → %-10s 成交价=¥%s%n",
                branchOf(state), state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow());
    }

    @Test
    @DisplayName("施压比价 → 仍走正常还价，但话术换了一种说法（价格不动）")
    void 施压比价只改措辞不改价格() {
        OverAllState state = run("t-pressure", "闲鱼上别人比你便宜多了", null);

        assertThat(branchOf(state)).isEqualTo("COUNTERED");
        assertThat((BigDecimal) state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow())
                .isEqualByComparingTo(SECOND_QUOTE);

        String reply = state.value(NegotiationGraphConfig.KEY_REPLY, "");
        assertThat(NegotiationTalkGenerator.PRICE_IN_TALK.matcher(reply).find())
                .as("正常还价分支必须报出价格：%s", reply)
                .isTrue();

        System.out.printf("[接通] 施压比价    → %-10s 报价=¥%s  话术=%s%n",
                branchOf(state), state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow(), reply);
    }

    // ==================================================================
    // ② 风控优先于意图（本测试最重要的一条）
    // ==================================================================

    @Test
    @DisplayName("⭐ 带越界出价的投诉：仍判 SUSPENDED，不能拿'投诉'绕过风控")
    void 风控优先于意图() {
        // 这句话同时命中 COMPLAINT（"投诉"）与出价 ¥800（< 底价 820）
        OverAllState state = run("t-guard-first", "800 你卖不卖，不卖我就投诉你", new BigDecimal("800"));

        assertThat(state.value(NegotiationGraphConfig.KEY_INTENT, ""))
                .as("意图确实被识别成了 COMPLAINT")
                .isEqualTo("COMPLAINT");
        assertThat(branchOf(state))
                .as("但出口必须是价格越界，而不是投诉转人工 —— 否则这次越界不留痕")
                .isEqualTo("SUSPENDED");
        assertThat(state.value(NegotiationGraphConfig.KEY_SUSPEND_REASON, ""))
                .isEqualTo("OFFER_BELOW_FLOOR");

        System.out.printf("[风控] 投诉+越界    → %-10s 原因=%s（意图=COMPLAINT 被风控覆盖）%n",
                branchOf(state), state.value(NegotiationGraphConfig.KEY_SUSPEND_REASON, ""));
    }

    @Test
    @DisplayName("区间内的出价仍然正常还价（没有被新加的意图路由误伤）")
    void 区间内出价不受影响() {
        OverAllState state = run("t-in-range", "那 830 呢", new BigDecimal("830"));

        assertThat(branchOf(state)).isEqualTo("COUNTERED");

        System.out.printf("[回归] 区间内出价  → %-10s（¥830 ≥ 底价 ¥820，正常还价）%n", branchOf(state));
    }

    @Test
    @DisplayName("出价 ≥ 我方下一轮报价 → ACCEPTED（原有行为未变）")
    void 出价达线即成交() {
        // 轮次 2 的设计报价：1000 − floor(180 × p(2)) = 1000 − 108 = ¥892，出这个数即达线。
        // ⚠️ 单位容易写错：LIST_FEN 是【分】，转成元必须用 BigDecimal.valueOf(x, 2)。
        BigDecimal headroomYuan = BigDecimal.valueOf(LIST_FEN - FLOOR_FEN, 2);
        BigDecimal ratio = PriceFormulas.ratioAt(DAYS_LISTED, PriceFormulas.BASE_ANCHOR_RATIO, 2);
        BigDecimal offer = BigDecimal.valueOf(LIST_FEN, 2)
                .subtract(headroomYuan.multiply(ratio).setScale(0, java.math.RoundingMode.DOWN));

        assertThat(offer).isEqualByComparingTo(SECOND_QUOTE);

        OverAllState state = run("t-accept", "就 " + offer.toPlainString() + " 吧", offer);

        assertThat(branchOf(state)).isEqualTo("ACCEPTED");
        System.out.printf("[回归] 出价达线      → %-10s 出价=¥%s 成交价=¥%s%n",
                branchOf(state), offer.toPlainString(),
                state.value(NegotiationGraphConfig.KEY_QUOTE).orElseThrow());
    }
}
