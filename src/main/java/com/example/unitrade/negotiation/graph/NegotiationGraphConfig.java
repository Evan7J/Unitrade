package com.example.unitrade.negotiation.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverConstant;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;
import com.example.unitrade.negotiation.pricing.AuthorizationGate;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PricingEngine;
import com.example.unitrade.negotiation.talk.LlmUsageRecorder;
import com.example.unitrade.negotiation.talk.NegotiationTalkGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 议价链路的 Graph 状态图（对齐简历要点 1「受控编排」）。
 *
 * <h2>为什么要把流程固化成图，而不是写一串 if</h2>
 * 议价链路有 5 个环节、4 个出口分支。写成过程式代码也能跑，
 * 但你会失去三样东西：
 * <ol>
 *   <li><b>成本可控</b>：图里每个节点是显式的一步。要回答"这次议价一共调了几次模型、
 *       花了多少钱"，只需要看节点定义；而 if 链里的模型调用是隐式的，想统计只能靠埋点撒得到处都是；</li>
 *   <li><b>过程可复现</b>：节点间靠 Schema（{@link #KEY_*}）传递数据，
 *       输入输出有明确契约，出了问题能把中间态完整打印出来；</li>
 *   <li><b>状态可持久化</b>：Checkpoint 落在图的框架层，人工接管后能从断点续跑。</li>
 * </ol>
 *
 * <h2>图结构</h2>
 * <pre>
 *   START → intent → pricing → gate ─┬─ TALK   → talk → END
 *                                    ├─ HUMAN  → human_takeover(interruptBefore) → END
 *                                    └─ REFUSE → refuse → talk → END
 * </pre>
 *
 * <h2>⚠️ 两条必须讲清的编排规则</h2>
 * <ol>
 *   <li><b>风控优先于意图</b>：只要买家报了价，先过授权闸门，再看意图。
 *       反过来的话，买家只要加一句"我要投诉"，一次低于底价的出价就能绕过留痕 ——
 *       那就等于给风控开了一个由用户话术触发的后门。
 *       （对应代码里 gate 节点 ① 与 ② 的先后顺序）</li>
 *   <li><b>意图只改路由与措辞，不改数值</b>：9 类意图里，只有 3 类会改变流程
 *       （投诉→人工、套底价→拒答、确认成交→接受）；
 *       施压比价 / 附加条件 / 闲聊只影响话术怎么写，<b>价格一律由定价引擎说了算</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 一处必须说清的取舍：Checkpoint 用 MemorySaver，而不是 RedisSaver</h2>
 * graph-core 提供了 {@code MemorySaver / FileSystemSaver / MongoSaver / RedisSaver} 四种。
 * 这里刻意选内存版，原因是<b>状态里含有底价</b>（定价节点必须读它）：
 * <ul>
 *   <li>落盘到文件或 Redis 意味着底价被<b>序列化到了第二个地方</b>——
 *       而"底价只存在于一个受控位置"是这个项目反复强调的约束；</li>
 *   <li>换 RedisSaver 之前，正确的做法是先把底价从 state 中移出去
 *       （改成 state 只存 sessionId，定价节点回查数据库），而不是直接开持久化。</li>
 * </ul>
 * 真正的会话状态持久化由 {@code t_negotiation_session} 表承担 ——
 * 那是经过设计的、访问受控的落点。断点续跑也基于它，见
 * {@code NegotiationGraphRunner.resumeAfterHuman}。
 */
@Configuration
public class NegotiationGraphConfig {

    /**
     * 议价轮次上限（图内闸门用）。
     *
     * <p><b>它和服务层 {@code negotiation.max-rounds} 是同一个配置项</b>，
     * 必须同源 —— 曾经这里用的是 {@code AuthorizationGate.DEFAULT_MAX_ROUNDS = 20}，
     * 而服务层实际按 5 轮截断，于是图内那道轮次检查<b>永远走不到</b>（先是 5 轮就被服务层拦了）。
     *
     * <p>两个地方各写一个上限，就是"双重真相"：改一处不漏另一处，
     * 行为会随执行路径不同而不同。所以这里改成从配置注入、构造期固定。
     */
    private final int maxRounds;

    /**
     * 意图分类用哪个实现：{@code rule}（默认）或 {@code llm}。
     *
     * <p>默认走规则版，是为了保住"可复现"这条硬要求 ——
     * Macro-F1 是在规则版上测出来的，如果默认改成模型版，
     * 那个 91.2% 就不再对应任何一段确定的代码，也就没法复现了。
     *
     * <p>模型版的意义在于<b>长尾表达</b>：真实用户不会按你的词表说话。
     * 它同时是"分类走轻量模型"这个降本手段的落点（见 {@code ModelRouter}）。
     */
    private final String intentClassifierMode;

    public NegotiationGraphConfig(@Value("${negotiation.max-rounds:5}") int maxRounds,
                                  @Value("${negotiation.intent.classifier:rule}") String intentClassifierMode) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("议价轮次上限必须 ≥ 1，实际=" + maxRounds);
        }
        this.maxRounds = maxRounds;
        this.intentClassifierMode = intentClassifierMode;
    }

    // ── 状态 Schema：节点之间只通过这几个 key 通信 ──────────────────────────
    /** 买家原话 */
    public static final String KEY_BUYER_MESSAGE = "buyerMessage";
    /** 意图识别结果 */
    public static final String KEY_INTENT = "intent";
    /** 授权区间（含底价，仅内存传递，不落盘） */
    public static final String KEY_RANGE = "range";
    /** 策略名 */
    public static final String KEY_STRATEGY = "strategy";
    /** 轮次 */
    public static final String KEY_ROUND = "round";
    /** 买家出价（元） */
    public static final String KEY_OFFER = "offer";
    /** 上一轮报价（元）。拒绝套底价那一轮要沿用旧价，不能因为被问一次就降一次价。 */
    public static final String KEY_PREV_QUOTE = "prevQuote";
    /** 引擎算出的报价（元） */
    public static final String KEY_QUOTE = "quote";
    /** 闸门判定分支：ACCEPTED / COUNTERED / SUSPENDED / ESCALATED / REFUSED / STALLED */
    public static final String KEY_BRANCH = "branch";
    /** 挂起原因（可为空） */
    public static final String KEY_SUSPEND_REASON = "suspendReason";
    /** 最终话术 */
    public static final String KEY_REPLY = "reply";
    /** 本轮输入 token（成本埋点，由 talk 节点产出） */
    public static final String KEY_PROMPT_TOKENS = "promptTokens";
    /** 本轮输出 token（成本埋点，由 talk 节点产出） */
    public static final String KEY_COMPLETION_TOKENS = "completionTokens";
    /** 本轮实际调用的模型名（成本口径可追溯） */
    public static final String KEY_MODEL = "model";

    // ── 节点名 ────────────────────────────────────────────────────────────
    public static final String NODE_INTENT = "intent";
    public static final String NODE_PRICING = "pricing";
    public static final String NODE_GATE = "gate";
    public static final String NODE_TALK = "talk";
    public static final String NODE_HUMAN = "human_takeover";
    public static final String NODE_REFUSE = "refuse";

    /**
     * 条件边的路由表。
     *
     * <p>注意这张表是<b>编译期校验</b>的：路由函数返回的字符串必须在表里，
     * 否则 {@code compile()} 就会抛 {@code GraphStateException}。
     * 这正是"用图而不是用 if"的一个实际收益 ——
     * 漏写一个分支在启动时就暴露，而不是等线上走到那个分支才发现。
     */
    private static final Map<String, String> GATE_ROUTES = Map.of(
            "TALK", NODE_TALK,
            "HUMAN", NODE_HUMAN,
            "REFUSE", NODE_REFUSE,
            "DONE", StateGraph.END);

    @Bean
    public CompiledGraph negotiationGraph(IntentClassifier intentClassifier,
                                          LlmIntentClassifier llmIntentClassifier,
                                          NegotiationTalkGenerator talkGenerator,
                                          LlmUsageRecorder usageRecorder) {
        try {
            return buildNegotiationGraph(intentClassifier, llmIntentClassifier, talkGenerator, usageRecorder);
        } catch (GraphStateException e) {
            // 图结构不合法 → 应用启动即失败。
            // 这是刻意的：一个结构有问题的图，跑起来只会产生难以归因的错误行为。
            throw new IllegalStateException("议价 Graph 结构校验失败：" + e.getMessage(), e);
        }
    }

    /**
     * 真正构建图的方法。
     *
     * <p>整个构建过程都可能抛 {@link GraphStateException}（受检异常）：
     * 缺 START 出边、条件边的路由表与路由函数返回值对不上、边指向不存在的节点，
     * 都会在这一步被拦下。<b>这就是"编译期校验"的实际价值</b> ——
     * 结构错误在应用启动时暴露，而不是等线上真的走到那个分支才发现。
     */
    private CompiledGraph buildNegotiationGraph(IntentClassifier intentClassifier,
                                                LlmIntentClassifier llmIntentClassifier,
                                                NegotiationTalkGenerator talkGenerator,
                                                LlmUsageRecorder usageRecorder)
            throws GraphStateException {

        // 定价引擎与闸门都是无状态纯函数，直接构造（不需要 Spring 管理）。
        // ⚠️ 闸门的轮次上限必须与服务层同源，见本类 maxRounds 字段的说明。
        PricingEngine pricingEngine = BuiltInStrategies.defaultEngine();
        AuthorizationGate gate = new AuthorizationGate(maxRounds);

        // 意图分类走哪一个：规则版（可复现）还是模型版（能兜长尾）。
        // ⚠️ llmIntentClassifier 可能为 null —— 脱离 Spring 的单测不会装配它，
        //    那种情况下必须能退回规则版，而不是抛 NPE 把整张图搞崩。
        java.util.function.Function<String, String> classify =
                "llm".equalsIgnoreCase(intentClassifierMode) && llmIntentClassifier != null
                        ? llmIntentClassifier::classify
                        : intentClassifier::classify;

        StateGraph graph = new StateGraph("negotiation", NegotiationGraphConfig::newState);

        // ── 节点 1：意图识别 ────────────────────────────────────────────────
        graph.addNode(NODE_INTENT, node_async(state -> Map.<String, Object>of(
                KEY_INTENT, classify.apply(state.value(KEY_BUYER_MESSAGE, "")))));

        // ── 节点 2：定价（模型不参与任何数值）────────────────────────────────
        graph.addNode(NODE_PRICING, node_async(state -> {
            AuthorizedRange range = (AuthorizedRange) state.value(KEY_RANGE).orElseThrow();
            String strategy = state.value(KEY_STRATEGY, BuiltInStrategies.PRODUCTION_STRATEGY);
            int round = state.value(KEY_ROUND, 1);
            Quote quote = pricingEngine.quoteWith(strategy, range, round);
            return Map.<String, Object>of(
                    KEY_QUOTE, quote.price().toYuan(),
                    KEY_BRANCH, "PENDING",
                    // 报价被安全网裁剪过 → 说明策略算出了越界值，必须留痕排查
                    "clamped", quote.clamped());
        }));

        // ── 节点 3：闸门（风控判定 + 意图路由）────────────────────────────────
        //
        // ⚠️ 下面的判定顺序是这一版最重要的设计决定，不能随意调换：
        //    ① 有出价 → 先过授权闸门（风控）
        //    ② 无出价 → 才轮到意图决定出口
        //
        //    如果反过来（先看意图再看价格），买家只要说"800 不卖我就投诉"，
        //    就会被路由去转人工，而那次低于底价的出价既不会被拦截、也不会留痕 ——
        //    指标直接失真。**意图路由不能凌驾于风控之上**，这是安全边界。
        graph.addNode(NODE_GATE, node_async(state -> {
            AuthorizedRange range = (AuthorizedRange) state.value(KEY_RANGE).orElseThrow();
            BigDecimal quoteYuan = (BigDecimal) state.value(KEY_QUOTE).orElseThrow();
            BigDecimal offerYuan = (BigDecimal) state.value(KEY_OFFER).orElse(null);
            String intent = state.value(KEY_INTENT, IntentClassifier.Intent.UNKNOWN.name());
            Quote quote = new Quote(Money.ofYuan(quoteYuan), state.value(KEY_ROUND, 1),
                    BuiltInStrategies.PRODUCTION_STRATEGY, false);

            // ① 有出价：风控先行。三种价格类结局和之前完全一致，一个字节都没变 ——
            //    新增的只有"正常还价区间里，如果是投诉意图则改判转人工"。
            if (offerYuan != null) {
                BargainOutcome outcome = gate.checkBuyerOffer(Money.ofYuan(offerYuan), range, quote);
                return switch (outcome) {
                    case BargainOutcome.Suspended s -> Map.<String, Object>of(
                            KEY_BRANCH, "SUSPENDED",
                            KEY_SUSPEND_REASON, s.reason().name());
                    case BargainOutcome.Accepted a -> Map.<String, Object>of(
                            KEY_BRANCH, "ACCEPTED",
                            KEY_QUOTE, a.price().toYuan());
                    default -> IntentClassifier.Intent.COMPLAINT.name().equals(intent)
                            // 价格在区间内，但买家已经有情绪了 —— 不该再跟他自动讨价还价
                            ? Map.<String, Object>of(
                                    KEY_BRANCH, "ESCALATED",
                                    KEY_SUSPEND_REASON,
                                    BargainOutcome.SuspensionReason.INTENT_COMPLAINT.name())
                            : Map.<String, Object>of(KEY_BRANCH, "COUNTERED");
                };
            }

            // ② 没有出价：意图直接决定出口。
            //    这三条就是"意图识别真正接通了动作"的证据 ——
            //    修复之前，intent 写进 state 之后没有任何地方读它。
            return switch (intent) {
                // 套底价 → 拒绝回答，但价格沿用上一轮（不能因为被问一次就降一次价）
                case "PROBE_FLOOR" -> Map.<String, Object>of(KEY_BRANCH, "REFUSED");
                // 情绪投诉 → 挂起转人工（不写风控留痕，理由见 SuspensionReason.INTENT_COMPLAINT）
                case "COMPLAINT" -> Map.<String, Object>of(
                        KEY_BRANCH, "ESCALATED",
                        KEY_SUSPEND_REASON,
                        BargainOutcome.SuspensionReason.INTENT_COMPLAINT.name());
                // 确认成交（且没有报新价格）→ 视为接受我方当前报价
                case "CONFIRM" -> Map.<String, Object>of(
                        KEY_BRANCH, "ACCEPTED",
                        KEY_QUOTE, quoteYuan);
                default -> Map.<String, Object>of(KEY_BRANCH, "COUNTERED");
            };
        }));

        // ── 节点 3.5：拒绝套底价（不含任何价格数字，话术会被反向校验）─────────
        //
        //    这一轮【不重新定价】而是沿用上一轮报价。
        //    原因：如果每次被问"你底价多少"就顺带报一个新价，等于给了买家一个
        //    "多问几次价格就会自己往下走"的杠杆；而拒绝透露底价本身不该换来任何让价。
        graph.addNode(NODE_REFUSE, node_async(state -> Map.<String, Object>of(
                KEY_BRANCH, "REFUSED",
                KEY_QUOTE, state.value(KEY_PREV_QUOTE).orElseGet(
                        () -> state.value(KEY_QUOTE).orElseThrow()))));

        // ── 节点 4：话术（语言的表达，只负责把定好的数字说体面）─────────────────
        graph.addNode(NODE_TALK, node_async(state -> {
            // 注意 TalkContext 里没有底价 —— 物理上进不了 prompt
            String reply = talkGenerator.generate(new NegotiationTalkGenerator.TalkContext(
                    state.value("productTitle", ""),
                    state.value("condition", 0),
                    (BigDecimal) state.value("listPrice").orElse(null),
                    (BigDecimal) state.value(KEY_QUOTE).orElse(null),
                    state.value(KEY_BUYER_MESSAGE, ""),
                    state.value(KEY_BRANCH, "COUNTERED"),
                    state.value(KEY_ROUND, 1),
                    // 意图只进"措辞"这一层：施压比价要共情、问详情要答问题 ——
                    // 但价格数字仍然是上面那个 engineQuote，一个字都不会被意图改动。
                    state.value(KEY_INTENT, IntentClassifier.Intent.UNKNOWN.name())));

            // 成本埋点在这里取走：写和读都在本节点执行的同一个线程上，
            // 所以 ThreadLocal 能正确隔离并发会话（见 LlmUsageRecorder 的注释）。
            LlmUsageRecorder.TokenUsage usage = usageRecorder.drain();
            Map<String, Object> out = new HashMap<>();
            out.put(KEY_REPLY, reply);
            out.put(KEY_PROMPT_TOKENS, usage.promptTokens());
            out.put(KEY_COMPLETION_TOKENS, usage.completionTokens());
            if (usage.model() != null) {
                out.put(KEY_MODEL, usage.model());
            }
            return out;
        }));

        // ── 节点 5：人工接管（HITL 断点，interruptBefore 会挡在它之前）────────
        graph.addNode(NODE_HUMAN, node_async(state -> Map.<String, Object>of(
                KEY_REPLY, "已转人工，卖家本人会尽快回复你")));

        // ── 边 ──────────────────────────────────────────────────────────────
        graph.addEdge(StateGraph.START, NODE_INTENT);
        graph.addEdge(NODE_INTENT, NODE_PRICING);
        graph.addEdge(NODE_PRICING, NODE_GATE);
        graph.addConditionalEdges(NODE_GATE,
                edge_async(state -> {
                    String branch = state.value(KEY_BRANCH, "COUNTERED");
                    // ESCALATED 与 SUSPENDED 都去人工节点，但语义不同：
                    //   SUSPENDED = 出价越界（价格问题）
                    //   ESCALATED = 买家情绪投诉（人的问题）
                    // 两者在下游落库时的处理也不同（前者写风控留痕，后者不写）。
                    if ("SUSPENDED".equals(branch) || "ESCALATED".equals(branch)) {
                        return "HUMAN";
                    }
                    if ("REFUSED".equals(branch)) {
                        return "REFUSE";
                    }
                    return "TALK";
                }),
                GATE_ROUTES);
        // 拒绝套底价也要出话术 —— 走同一条"话术必经之路"，
        // 这样数值校验（这里是反向校验：不许出现价格）不会被绕开。
        graph.addEdge(NODE_REFUSE, NODE_TALK);
        graph.addEdge(NODE_TALK, StateGraph.END);
        graph.addEdge(NODE_HUMAN, StateGraph.END);

        // Checkpoint Saver 配置。
        //
        // ⚠️ 这套 API 是「两层配置」，形状比较绕，文档里看不出来，需要读签名确认：
        //     CompileConfig.Builder 上没有 checkpointSaver(Saver)；
        //     要先构造 SaverConfig，再用 register(type, saver) 把实现注册进去，
        //     最后把 SaverConfig 交给 CompileConfig.builder().saverConfig(...)。
        //    type 取自 SaverConstant（MEMORY / REDIS / FILE / DB）。
        SaverConfig saverConfig = SaverConfig.builder()
                .register(SaverConstant.MEMORY, new MemorySaver())
                .build();

        return graph.compile(CompileConfig.builder()
                // HITL：执行到 human_takeover 之前挂起，把控制权交回调用方。
                // 选静态断点（interruptBefore）而不是动态 InterruptableAction：
                // 转人工是固定的合规点，不值得为它引入运行时条件判断的复杂度。
                .interruptBefore(NODE_HUMAN)
                .saverConfig(saverConfig)
                .build());
    }

    /**
     * 状态工厂 —— 在这里声明所有 key 及其合并策略。
     *
     * <p><b>没在这里注册过的 key，节点写进去会直接抛异常。</b>
     * 这个"报错式"约束是故意保留的：它逼着每新增一个状态字段都显式声明一次，
     * 而不是靠"我记得哪里用过"。
     *
     * <p>策略选择规则（一句话）：<b>表示"当前状态"的用 Replace，表示"历史累积"的用 Append。</b>
     * 这里全是当前状态，所以清一色 Replace。
     */
    private static OverAllState newState() {
        OverAllState state = new OverAllState();
        ReplaceStrategy replace = new ReplaceStrategy();
        state.registerKeyAndStrategy(KEY_BUYER_MESSAGE, replace);
        state.registerKeyAndStrategy(KEY_INTENT, replace);
        state.registerKeyAndStrategy(KEY_RANGE, replace);
        state.registerKeyAndStrategy(KEY_STRATEGY, replace);
        state.registerKeyAndStrategy(KEY_ROUND, replace);
        state.registerKeyAndStrategy(KEY_OFFER, replace);
        state.registerKeyAndStrategy(KEY_PREV_QUOTE, replace);
        state.registerKeyAndStrategy(KEY_QUOTE, replace);
        state.registerKeyAndStrategy(KEY_BRANCH, replace);
        state.registerKeyAndStrategy(KEY_SUSPEND_REASON, replace);
        state.registerKeyAndStrategy(KEY_REPLY, replace);
        state.registerKeyAndStrategy(KEY_PROMPT_TOKENS, replace);
        state.registerKeyAndStrategy(KEY_COMPLETION_TOKENS, replace);
        state.registerKeyAndStrategy(KEY_MODEL, replace);
        state.registerKeyAndStrategy("clamped", replace);
        state.registerKeyAndStrategy("productTitle", replace);
        state.registerKeyAndStrategy("condition", replace);
        state.registerKeyAndStrategy("listPrice", replace);
        return state;
    }
}
