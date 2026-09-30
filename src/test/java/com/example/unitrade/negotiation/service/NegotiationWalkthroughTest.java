package com.example.unitrade.negotiation.service;

import com.example.unitrade.entity.NegotiationSession;
import com.example.unitrade.negotiation.adapter.NegotiationPricingAdapter;
import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;
import com.example.unitrade.negotiation.graph.IntentClassifier;
import com.example.unitrade.negotiation.pricing.AuthorizationGate;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import com.example.unitrade.negotiation.pricing.PricingEngine;
import com.example.unitrade.negotiation.talk.NegotiationTalkGenerator;
import com.example.unitrade.negotiation.talk.TemplateTalkGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 把一组具体参数走一遍完整议价，把「买家说 → 判定 → 我方报价 → 实际回复」打印出来。
 *
 * <h2>为什么需要这个测试</h2>
 * 单测断言的是"每个零件对不对"，但「买家砍价的时候系统到底会回什么」是<b>零件串起来之后</b>
 * 才看得见的行为。这一层没有断言价值（它不验证正确性），但很有<b>沟通价值</b>：
 * 它是能一眼看懂"这套策略长什么样"的产物，也方便拿真实数字讨论产品问题。
 *
 * <p>它刻意只用模板话术生成器：<b>可复现</b>，不依赖模型、不依赖数据库、不依赖 Spring。
 * 换成 LLM 话术之后措辞会变，但价格和分支不会变 —— 而价格和分支才是这份走查要说明的东西。
 *
 * <p>前三个用例用三参 {@link AuthorizedRange}（基准锚点、无扰动），数字是整齐的"设计值"；
 * 第四个用例专门展示生产路径上 ±15% 的会话扰动。
 */
class NegotiationWalkthroughTest {

    /** 卖家成本 ¥600 —— 但<b>系统完全不知道这个数</b>，系统只认下面那个底价。 */
    private static final long SELLER_COST_FEN = 60_000L;

    private static final long LIST_FEN = 100_000L;    // 挂牌 ¥1000
    private static final long FLOOR_FEN = 82_000L;    // 底价 ¥820（"只接受 820 以上"）
    private static final int DAYS_LISTED = 0;         // 刚上架

    private final PricingEngine engine = BuiltInStrategies.defaultEngine();
    private final AuthorizationGate gate = new AuthorizationGate();
    private final TemplateTalkGenerator talk = new TemplateTalkGenerator();
    private final IntentClassifier intent = new IntentClassifier();

    private final AuthorizedRange range =
            new AuthorizedRange(new Money(LIST_FEN), new Money(FLOOR_FEN), DAYS_LISTED);

    /**
     * 一次「买家出价」的完整结果。
     *
     * @param engineQuote 定价引擎为轮次算出的报价（不管闸门怎么判，它都存在）
     * @param price       判定为 ACCEPTED 时是成交价；否则等于 engineQuote
     */
    private record Turn(int round, BigDecimal engineQuote, BigDecimal price, String branch, String reply) {
    }

    private Turn opening() {
        Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, 1);
        return new Turn(1, quote.price().toYuan(), quote.price().toYuan(), "COUNTERED",
                reply("", quote.price().toYuan(), "COUNTERED", 1));
    }

    /**
     * ⚠️ 一个容易搞错、但必须说清的行为：闸门是拿<b>买家出价</b>与
     * <b>「下一轮」报价</b>比较的，而不是与"买家刚刚看到的那一轮报价"比较。
     *
     * <p>原因是执行顺序：买家出价 → 轮次 +1 → 定价节点先算出新报价 → 闸门才判定。
     * 所以买家只要出到"我方下一轮本来就要报的数"，就立刻成交 ——
     * 语义是"你出到了我下一步要报的价，那就没有必要再往下走了"。
     * 这个方向其实<b>对卖家有利</b>：否则我们要么继续让价，要么把这单拖下去。
     */
    private Turn offer(int round, String buyerMessage, long offerYuan) {
        Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, round);
        BargainOutcome outcome = gate.checkBuyerOffer(Money.ofYuan(BigDecimal.valueOf(offerYuan)), range, quote);

        String branch;
        BigDecimal price;
        switch (outcome) {
            case BargainOutcome.Accepted accepted -> {
                branch = "ACCEPTED";
                price = accepted.price().toYuan();
            }
            case BargainOutcome.Suspended ignored -> {
                branch = "SUSPENDED";
                price = quote.price().toYuan();
            }
            default -> {
                branch = "COUNTERED";
                price = quote.price().toYuan();
            }
        }
        return new Turn(round, quote.price().toYuan(), price,
                branch, reply(buyerMessage, price, branch, round));
    }

    private String reply(String buyerMessage, BigDecimal quoteYuan, String branch, int round) {
        return talk.generate(new NegotiationTalkGenerator.TalkContext(
                "E2E-走查商品", 2, BigDecimal.valueOf(LIST_FEN, 2), quoteYuan, buyerMessage, branch, round,
                intent.classify(buyerMessage)));
    }

    private static String yuan(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    private static String yuan(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ==================================================================

    @Test
    @DisplayName("① 报价阶梯：成本 600 / 挂牌 1000 / 底价 820，每一轮报多少")
    void 报价阶梯() {
        long headroom = LIST_FEN - FLOOR_FEN;
        BigDecimal pMax = PriceFormulas.pMax(DAYS_LISTED);
        long limitFen = LIST_FEN - Math.round(headroom * pMax.doubleValue());

        System.out.println();
        System.out.println("════════ 参数 ════════");
        System.out.printf("卖家成本         ¥%s   ← 系统不知道这个数，它只是卖家心里的账%n", yuan(SELLER_COST_FEN));
        System.out.printf("挂牌价           ¥%s%n", yuan(LIST_FEN));
        System.out.printf("底价（授权下限）  ¥%s   ← 唯一进入系统的敏感输入%n", yuan(FLOOR_FEN));
        System.out.printf("可让空间 h       ¥%s   = 挂牌 − 底价，只占挂牌价的 %.0f%%%n",
                yuan(headroom), headroom * 100.0 / LIST_FEN);
        System.out.printf("刚上架 d=0       pMax = %s → 最多让出 %.0f%% 的 h = ¥%s%n",
                pMax, pMax.doubleValue() * 100,
                yuan(Math.round(headroom * pMax.doubleValue())));
        System.out.printf("整单毛利（若按挂牌成交） ¥%s；按底价成交也有 ¥%s → 无论怎么谈都不亏%n",
                yuan(LIST_FEN - SELLER_COST_FEN), yuan(FLOOR_FEN - SELLER_COST_FEN));
        System.out.println();

        System.out.println("════════ 报价阶梯 ════════");
        System.out.printf("%-6s %-14s %-12s %-14s%n", "轮次", "已让出比例 p(n)", "让出金额", "我方报价");
        for (int round = 1; round <= 8; round++) {
            BigDecimal ratio = PriceFormulas.ratioAt(DAYS_LISTED, round);
            Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, round);
            System.out.printf("%-6d %-14s %-12s ¥%s%n",
                    round, String.format("%.4f", ratio),
                    yuan(Math.round(headroom * ratio.doubleValue())), yuan(quote.price().toYuan()));
        }
        System.out.printf("%-6s %-14s %-12s ¥%s   ← 极限，永远到不了%n",
                "∞", pMax.toPlainString(), yuan(Math.round(headroom * pMax.doubleValue())), yuan(limitFen));
        System.out.printf("%n安全垫 = ¥%s（可让空间的 %.0f%%）→ 报价永远碰不到底价 ¥%s%n",
                yuan(limitFen - FLOOR_FEN), (limitFen - FLOOR_FEN) * 100.0 / headroom, yuan(FLOOR_FEN));
        System.out.println();
    }

    @Test
    @DisplayName("② 场景 A：买家从 850 一点点加，最后在 865 成交")
    void 场景A_买家加价到最后成交() {
        System.out.println();
        System.out.println("════════ 场景 A：买家一路加价，最后成交 ════════");

        Turn open = opening();
        System.out.println("【轮 1】买家：我想便宜点");
        System.out.printf("        意图=%s   我方首轮报价 ¥%s%n", intent.classify("我想便宜点"), yuan(open.engineQuote()));
        System.out.printf("        回复：%s%n%n", open.reply());

        Offer[] offers = {
                new Offer(850L, "850 行不行"),
                new Offer(860L, "那 860 总行了吧"),
                new Offer(865L, "865，就这么定了"),
        };

        int round = 2;
        for (Offer o : offers) {
            Turn turn = offer(round, o.message(), o.yuan());
            System.out.printf("【轮 %d】买家：%s（出价 ¥%d）%n", round, o.message(), o.yuan());
            System.out.printf("        意图=%s   引擎报价 ¥%s → 判定=%s%n",
                    intent.classify(o.message()), yuan(turn.engineQuote()), turn.branch());
            if ("ACCEPTED".equals(turn.branch())) {
                System.out.printf("        报价被替换为成交价：¥%s%n", yuan(turn.price()));
            }
            System.out.printf("        回复：%s%n%n", turn.reply());
            round++;
        }

        System.out.println("判定顺序：「出价 ≥ 挂牌价 → 出价 ≥ 我方下一轮报价 → 出价 < 底价 → 还价」。");
        System.out.println("注意第 4 轮：买家出 865，而引擎下一轮本来要报的就是 865 → 直接接受。");
        System.out.println("含义是「你出到了我下一步要报的价，那就没必要再往下走了」——方向对卖家有利。");
        System.out.println();
    }

    @Test
    @DisplayName("③ 场景 B：同样一轮，报 800 与报 830 的结果完全不同")
    void 场景B_越界与区间内的分界() {
        System.out.println();
        System.out.println("════════ 场景 B：差 30 块，一个转人工、一个正常还价 ════════");

        Turn open = opening();
        System.out.printf("【轮 1】我方首轮报价 ¥%s%n", yuan(open.engineQuote()));
        System.out.printf("        回复：%s%n%n", open.reply());

        // 出价 800 < 底价 820 → 越界
        Turn low = offer(2, "800 我现在就拍", 800L);
        System.out.println("【轮 2·甲】买家：800 我现在就拍（出价 ¥800）");
        System.out.printf("        意图=%s   引擎报价 ¥%s → 判定=%s%n",
                intent.classify("800 我现在就拍"), yuan(low.engineQuote()), low.branch());
        System.out.printf("        回复：%s%n", low.reply());
        System.out.println("        ↑ 800 < 底价 820 → 越界：写 t_bargain_guard_log 留痕 → 挂起 → 转人工。");
        System.out.println("          买家没做错任何事，所以不是报错给他，而是把决定权交回卖家本人。");
        System.out.println("          ⚠️ 这句话的意图其实被识别成 CONFIRM（含\"拍了\"），但判定仍然是 SUSPENDED ——");
        System.out.println("             风控先于意图。反过来的话，买家加一句\"我拍了\"就能让越界出价不留痕。");
        System.out.println();

        // 出价 830 ≥ 底价 820 → 正常还价
        Turn ok = offer(2, "那 830 呢", 830L);
        System.out.println("【轮 2·乙】买家：那 830 呢（出价 ¥830，同一轮）");
        System.out.printf("        意图=%s   引擎报价 ¥%s → 判定=%s%n",
                intent.classify("那 830 呢"), yuan(ok.engineQuote()), ok.branch());
        System.out.printf("        回复：%s%n", ok.reply());
        System.out.println("        ↑ 830 ≥ 底价 820，只是低于我方报价 → 正常还价，不转人工。");
        System.out.println("          这 30 块的差,就是「风控」和「正常谈判」的分界线。");
        System.out.println();
    }

    @Test
    @DisplayName("④ 会话扰动：同一挂牌/底价，不同商品的首轮报价不一样")
    void 扰动导致同款商品首轮报价不同() {
        System.out.println();
        System.out.println("════════ 会话扰动：同参数、不同商品的首轮差异 ════════");

        NegotiationPricingAdapter jittered = new NegotiationPricingAdapter(
                new BigDecimal("0.40"), PriceFormulas.MAX_ANCHOR_JITTER_BP, "walkthrough");

        System.out.printf("%-10s %-12s %-12s %-12s%n", "商品ID", "锚点比例", "首轮让出", "首轮报价");
        for (long productId = 1; productId <= 6; productId++) {
            NegotiationSession session = new NegotiationSession();
            session.setProductId(productId);
            session.setSellerId(100L);
            session.setSnapshotListPrice(BigDecimal.valueOf(LIST_FEN, 2));
            session.setSnapshotFloorPrice(BigDecimal.valueOf(FLOOR_FEN, 2));
            session.setSnapshotDaysListed(DAYS_LISTED);

            AuthorizedRange jitteredRange = jittered.toRange(session);
            Quote quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, jitteredRange, 1);

            System.out.printf("%-10d %-12s %-12s ¥%s%n",
                    productId,
                    jitteredRange.anchorRatio().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    yuan(LIST_FEN - quote.price().fen()),
                    yuan(quote.price().toYuan()));
        }
        System.out.println();
        System.out.println("同一商品永远同一行（可复现）；跨商品锚点不同 → 拿固定比例反解底价不再成立。");
        System.out.println();
    }

    private record Offer(long yuan, String message) {
    }
}
