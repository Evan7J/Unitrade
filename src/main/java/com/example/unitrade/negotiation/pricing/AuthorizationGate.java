package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.BargainOutcome.SuspensionReason;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;

/**
 * 授权闸门 —— 出价前/报价后的最后一道校验。
 *
 * <h2>它在链路里的位置（位置是被约束逼出来的，不是随手放的）</h2>
 * <pre>
 *   意图识别 → 定价（比例→价格） → 【闸门】 → 话术生成 → 发出
 * </pre>
 * <ul>
 *   <li>放在定价之前：没有东西可校验；</li>
 *   <li><b>放在话术之后：就已经没有意义了</b> —— 话术一旦说出去，等于已经对买家做了承诺。</li>
 * </ul>
 * 所以闸门必须在「对外承诺」之前。这条推理比"我在哪里加了个 if"重要得多。
 *
 * <h2>为什么返回 {@link BargainOutcome} 而不是另建一个 GateDecision 类型</h2>
 * 因为闸门的输出就是"本轮怎么办"，和 {@code BargainOutcome} 是同一件事。
 * 如果为了"闸门看起来更独立"再造一个类型，两套语义之间就要写映射代码，
 * 而<b>映射代码迟早会和某一侧的定义走歪</b>（这就是"双重真相"的代价）。
 * 一个概念只留一个类型。
 *
 * <h2>为什么越界是"挂起"而不是"拒绝"</h2>
 * 买家报低价是<b>完全合法的用户行为，不是程序错误</b>：
 * <ul>
 *   <li>直接拒绝会报错给用户，体验断裂（而且买家没有做错任何事）；</li>
 *   <li>静默裁剪会让人困惑，且可被用来试探系统边界；</li>
 * </ul>
 * 所以选「挂起 + 留痕 + 转人工」。这是<b>风控</b>，不是参数校验 ——
 * 参数校验保护系统（非法来源是 bug 或恶意调用），风控保护业务（不让卖家亏本）。
 */
public final class AuthorizationGate {

    /**
     * 议价轮次上限的<b>默认值</b> —— 仅用于"脱离 Spring 单独使用定价内核"的场景
     * （比如 {@code negotiation-kernel} 里的属性测试）。
     *
     * <p>⚠️ <b>生产路径不走这个值。</b>线上用的是配置项
     * {@code negotiation.max-rounds}（{@code application.yml} 里为 5），
     * 由 {@code NegotiationGraphConfig} 构造本类时注入 —— 图和 service 必须同源，
     * 否则会出现"服务层按 A 截断、图内按 B 判定"的双重真相。
     * （这里曾经就是 20 vs 5 不一致：图内那道轮次检查实际上永远走不到。）
     *
     * <p>注意区分两个"轮次"：本常量约束的是<b>议价轮次</b>（买卖双方来回几轮，超了转人工）；
     * 如果将来给话术生成加上 ReAct 工具调用，那种"Agent 自主调用工具的轮次预算"
     * 是另一个概念、另一个参数，别在面试里说成一个。
     */
    public static final int DEFAULT_MAX_ROUNDS = 20;

    private final int maxRounds;

    public AuthorizationGate() {
        this(DEFAULT_MAX_ROUNDS);
    }

    public AuthorizationGate(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("轮次上限必须 ≥ 1，实际=" + maxRounds);
        }
        this.maxRounds = maxRounds;
    }

    public int maxRounds() {
        return maxRounds;
    }

    /**
     * 校验<b>引擎产出的报价</b> —— 防的是"策略实现越界"这类编程错误，不是买家行为。
     *
     * <p>返回 {@code Countered} 表示"通过"：这个报价值得报给买家，
     * 语义上就是一次还价（Counter）。之所以不用别的方式表达"通过"，
     * 是为了让调用方拿到的永远是同一个封闭类型，分支处理不会漏。
     *
     * @param quote         引擎算出的报价
     * @param previousPrice 上一轮报价；首轮传 null
     */
    public BargainOutcome checkQuote(Quote quote, Money previousPrice) {
        if (quote.clamped()) {
            // 走到这里说明策略算出了低于底价的报价 —— 安全网救了它。
            // 这是告警信号，不是"防御成功"：兜底代码不配告警就是掩盖 bug。
            return new BargainOutcome.Suspended(
                    SuspensionReason.QUOTE_BELOW_FLOOR, quote.price());
        }
        if (previousPrice != null && quote.price().fen() > previousPrice.fen()) {
            // 报价回升。定价引擎是纯函数，正常不可能发生；
            // 出现即说明有缓存命中或状态被污染（见讲义第 9 讲的缓存事故）。
            return new BargainOutcome.Suspended(
                    SuspensionReason.QUOTE_NOT_MONOTONIC, quote.price());
        }
        if (quote.round() > maxRounds) {
            return new BargainOutcome.Suspended(
                    SuspensionReason.ROUND_LIMIT_EXCEEDED, quote.price());
        }
        return new BargainOutcome.Countered(quote);
    }

    /**
     * 校验<b>买家出价</b> —— 这就是"越界拦截"的判定点，也是评测指标的统计来源。
     *
     * <p>判定顺序是有讲究的：从"最容易成交"到"最需要拦截"，
     * 每一步都能被前面的条件短路掉。顺序改了语义就变了
     * （比如先判越界，那"出价高于挂牌价"也会被算成越界）。
     *
     * @param buyerOffer   买家出价
     * @param range        授权区间（含底价 —— 这是判定越界的唯一依据）
     * @param currentQuote 我方本轮报价
     */
    public BargainOutcome checkBuyerOffer(Money buyerOffer, AuthorizedRange range, Quote currentQuote) {
        Money counter = currentQuote.price();

        // ① 出价 ≥ 挂牌价：直接按挂牌价成交，不占买家便宜。
        //    现实中罕见，但"不可能发生"的分支往往是最容易写错的。
        if (buyerOffer.isAtLeast(range.listPrice())) {
            return new BargainOutcome.Accepted(range.listPrice(), currentQuote.round());
        }

        // ② 出价 ≥ 我方报价：接受。双方价格已经交叉，没有必要再还价。
        if (buyerOffer.isAtLeast(counter)) {
            return new BargainOutcome.Accepted(buyerOffer, currentQuote.round());
        }

        // ③ 出价 < 底价：越界，挂起转人工并留痕。
        //    ⚠️ 这一行是"拦截 N 次"这个指标的唯一定义处。
        //    改这里等于改指标口径，必须同步更新 README 与评测报告。
        if (buyerOffer.isLessThan(range.floorPrice())) {
            return new BargainOutcome.Suspended(SuspensionReason.OFFER_BELOW_FLOOR, buyerOffer);
        }

        // ④ 正常还价区间 [底价, 我方报价)：给出还价。
        return new BargainOutcome.Countered(currentQuote);
    }
}
