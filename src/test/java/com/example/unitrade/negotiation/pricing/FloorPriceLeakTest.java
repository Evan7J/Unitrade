package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.entity.NegotiationSession;
import com.example.unitrade.negotiation.adapter.NegotiationPricingAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「从公开报价反解底价」的攻防对照测试。
 *
 * <h2>这个测试想说明什么</h2>
 * 类型边界（{@code AuthorizedRange} 不出 pricing 包、对外的 {@code Quote} 没有 floor 字段）
 * 只能挡住<b>代码层面</b>的泄漏：日志、序列化、返回值。
 * 它<b>挡不住"从公开的报价序列反推底价"</b> —— 而后者根本不需要看代码。
 *
 * <p>本测试用真正的生产类（{@code BuiltInStrategies.PRODUCTION_STRATEGY} +
 * {@code NegotiationPricingAdapter}）跑两个对照：
 *
 * <table border="1">
 *   <caption>攻防对照</caption>
 *   <tr><th>场景</th><th>攻击者需要什么</th><th>实测反解误差</th></tr>
 *   <tr><td><b>锚点关闭扰动</b>（等于修复前的行为）</td>
 *       <td>只看第一轮报价</td><td><b>≤ 2 分</b>（等于没有保密）</td></tr>
 *   <tr><td><b>锚点开启扰动</b>（当前生产默认 ±15%）</td>
 *       <td>只看第一轮报价</td><td><b>≈ 可让空间的 7.5%，上界 15%</b></td></tr>
 * </table>
 *
 * <h2>攻击的两个变体</h2>
 * <ol>
 *   <li><b>攻击 A（知道锚点是常数）</b>：
 *       {@code 底价 = 挂牌价 − (挂牌价 − 首轮报价) ÷ 锚点比例}。一个数就够。</li>
 *   <li><b>攻击 B（不知道锚点，只知"让步按几何衰减"）</b>：
 *       {@code 第2轮让出额 ÷ 第1轮让出额 = p(2)/p(1) = 1 + q}，
 *       两轮就能先解出 q、再解出上架天数、最后解出可让空间。
 *       <b>修复前它和攻击 A 一样精确</b>；修复后它退化成与 A 相同的边界误差 ——
 *       因为扰动让 (上架天数, 扰动) 这一对参数在报价序列上<b>不可辨识</b>：
 *       不同的 (d, j) 组合能产生完全相同的 q。</li>
 * </ol>
 *
 * <h2>诚实的残余风险</h2>
 * 扰动<b>不是消除泄漏，而是把泄漏从"精确"降到"有界"</b>。
 * 反解出的底价误差上界 = 可让空间 × 扰动幅度（默认 ±15%）。
 * h = ¥400 时约 ±¥60 —— 仍然有信息量，只是不再能"精确命中"。
 * 想彻底消除只能不报价，那就不叫议价了。
 * 所以真正兜住风险的是另一条：<b>报价永远不低于底价</b>，
 * 因此泄漏损失的是溢价，不是本金。
 */
class FloorPriceLeakTest {

    /** 基准锚点比例（与生产默认一致）。 */
    private static final BigDecimal BASE_ANCHOR = new BigDecimal("0.40");

    /** 关闭扰动的适配器 —— 等价于修复前的行为，用于对照。 */
    private final NegotiationPricingAdapter noJitter =
            new NegotiationPricingAdapter(BASE_ANCHOR, 0, "leak-test-secret");

    /** 开启生产默认扰动的适配器。 */
    private final NegotiationPricingAdapter jittered =
            new NegotiationPricingAdapter(BASE_ANCHOR, PriceFormulas.MAX_ANCHOR_JITTER_BP, "leak-test-secret");

    private final PricingEngine engine = BuiltInStrategies.defaultEngine();

    private NegotiationSession session(long productId, long sellerId, long listFen, long floorFen, int days) {
        NegotiationSession s = new NegotiationSession();
        s.setProductId(productId);
        s.setSellerId(sellerId);
        s.setSnapshotListPrice(BigDecimal.valueOf(listFen, 2));
        s.setSnapshotFloorPrice(BigDecimal.valueOf(floorFen, 2));
        s.setSnapshotDaysListed(days);
        return s;
    }

    private long quoteFen(NegotiationPricingAdapter adapter, NegotiationSession s, int round) {
        return engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, adapter.toRange(s), round)
                .price().fen();
    }

    /** 攻击 A：把锚点当作已知常数 0.40。 */
    private static long recoverByAnchorOnly(long listFen, long firstQuoteFen) {
        long conceded = listFen - firstQuoteFen;
        long headroom = BigDecimal.valueOf(conceded)
                .divide(BASE_ANCHOR, 0, RoundingMode.HALF_UP)
                .longValueExact();
        return listFen - headroom;
    }

    /** 攻击 B：不知道锚点常数，用两轮报价解 q → 反解上架天数 → 反解可让空间。 */
    private static long recoverByTwoRounds(long listFen, long firstQuoteFen, long secondQuoteFen) {
        double conceded1 = listFen - firstQuoteFen;
        double conceded2 = listFen - secondQuoteFen;

        double decay = conceded2 / conceded1 - 1.0;              // = 1 + q − 1 = q
        double daysListed = (160.0 * decay - 80.0) / (1.0 - decay);
        double pMax = (160.0 + daysListed) / 200.0;
        double anchor = pMax * (1.0 - decay);

        return Math.round(listFen - conceded1 / anchor);
    }

    // ==================================================================
    // 对照 1：无扰动 —— 攻击精确成立（记录历史行为，防止有人把扰动关掉还以为是安全的）
    // ==================================================================

    @Test
    @DisplayName("对照组：关闭扰动时，只看一轮报价就能精确反解底价（实测 ≤2 分）")
    void 关闭扰动时攻击精确成立() {
        long maxErrorA = 0;
        long maxErrorB = 0;
        int n = 0;

        Random rnd = new Random(20260924L);
        for (int i = 0; i < 2000; i++) {
            long floorFen = 100L + rnd.nextInt(500_000);
            long headroom = 500L + rnd.nextInt(200_000);
            long listFen = floorFen + headroom;
            int days = rnd.nextInt(60);

            NegotiationSession s = session(i, 1L, listFen, floorFen, days);
            long q1 = quoteFen(noJitter, s, 1);
            long q2 = quoteFen(noJitter, s, 2);

            maxErrorA = Math.max(maxErrorA, Math.abs(recoverByAnchorOnly(listFen, q1) - floorFen));
            maxErrorB = Math.max(maxErrorB, Math.abs(recoverByTwoRounds(listFen, q1, q2) - floorFen));
            n++;
        }

        System.out.printf("%n[对照·无扰动] 样本=%d  攻击A 最大误差=%d 分  攻击B 最大误差=%d 分%n",
                n, maxErrorA, maxErrorB);
        System.out.println("[对照·无扰动] 只用了一个公开数字：第一轮报价。");

        assertThat(maxErrorA).as("无扰动时单轮反解应当非常精确").isLessThanOrEqualTo(3L);
        assertThat(maxErrorB).as("无扰动时双轮反解应当非常精确").isLessThanOrEqualTo(3L);
    }

    // ==================================================================
    // 对照 2：开启扰动 —— 攻击退化成"有界猜测"
    // ==================================================================

    @Test
    @DisplayName("修复后：锚点带 ±15% 扰动，反解误差被压到「扰动幅度 × 可让空间」")
    void 开启扰动后攻击被限制在扰动幅度内() {
        long maxErrorA = 0;
        long maxErrorB = 0;
        double maxRelative = 0;
        double sumRelative = 0;
        int n = 0;

        Random rnd = new Random(20260924L);
        for (int i = 0; i < 2000; i++) {
            long floorFen = 100L + rnd.nextInt(500_000);
            long headroom = 500L + rnd.nextInt(200_000);
            long listFen = floorFen + headroom;
            int days = rnd.nextInt(60);

            NegotiationSession s = session(i, 1L, listFen, floorFen, days);
            long q1 = quoteFen(jittered, s, 1);
            long q2 = quoteFen(jittered, s, 2);

            long errorA = Math.abs(recoverByAnchorOnly(listFen, q1) - floorFen);
            long errorB = Math.abs(recoverByTwoRounds(listFen, q1, q2) - floorFen);
            maxErrorA = Math.max(maxErrorA, errorA);
            maxErrorB = Math.max(maxErrorB, errorB);

            double relative = (double) errorA / headroom;
            maxRelative = Math.max(maxRelative, relative);
            sumRelative += relative;
            n++;
        }

        double jitterBound = PriceFormulas.MAX_ANCHOR_JITTER_BP / 10_000.0;
        System.out.printf("%n[修复·有扰动] 样本=%d  攻击A 最大相对误差=%.2f%%  平均=%.2f%%  上界=%.0f%%%n",
                n, maxRelative * 100, sumRelative / n * 100, jitterBound * 100);
        System.out.printf("[修复·有扰动] 攻击A 最大绝对误差=%d 分（¥%.2f）  攻击B 最大误差=%d 分%n",
                maxErrorA, maxErrorA / 100.0, maxErrorB);

        // 关键断言：修复必须让"精确反解"不再成立
        assertThat(maxRelative)
                .as("开启扰动后，反解相对误差不应再小于 5% 的可让空间")
                .isGreaterThan(0.05);
        // 同时误差必须仍然被扰动幅度界住 —— 否则说明扰动派生有 bug（比如落到了区间外）
        assertThat(maxRelative)
                .as("反解误差不应超过扰动幅度 + 取整噪声")
                .isLessThan(jitterBound + 0.01);
    }

    // ==================================================================
    // 对照 3：扰动本身的性质
    // ==================================================================

    @Test
    @DisplayName("扰动是「按商品确定性派生」的：同商品永远同值，不同商品几乎不会撞值")
    void 扰动按商品确定性派生() {
        NegotiationSession a1 = session(101L, 7L, 100_000L, 60_000L, 0);
        NegotiationSession a2 = session(101L, 7L, 100_000L, 60_000L, 0);
        NegotiationSession b = session(102L, 7L, 100_000L, 60_000L, 0);
        NegotiationSession otherSeller = session(101L, 8L, 100_000L, 60_000L, 0);

        assertThat(quoteFen(jittered, a1, 1))
                .as("同一商品 + 同一卖家必须永远派生同一个锚点")
                .isEqualTo(quoteFen(jittered, a2, 1));
        assertThat(quoteFen(jittered, a1, 1))
                .as("换一个商品应得到不同锚点（否则攻击者可跨商品取极值反推）")
                .isNotEqualTo(quoteFen(jittered, b, 1));
        assertThat(quoteFen(jittered, a1, 1))
                .as("换一个卖家也应得到不同锚点")
                .isNotEqualTo(quoteFen(jittered, otherSeller, 1));
    }

    @Test
    @DisplayName("即便开启扰动，多轮报价依然单调非增且永不低于底价")
    void 扰动不破坏两条核心性质() {
        Random rnd = new Random(20260925L);
        for (int i = 0; i < 300; i++) {
            long floorFen = 100L + rnd.nextInt(500_000);
            long headroom = 1000L + rnd.nextInt(200_000);
            long listFen = floorFen + headroom;
            int days = rnd.nextInt(60);

            NegotiationSession s = session(rnd.nextInt(100_000), 1L, listFen, floorFen, days);
            long previous = Long.MAX_VALUE;
            for (int round = 1; round <= 30; round++) {
                long quote = quoteFen(jittered, s, round);
                assertThat(quote).as("第 %d 轮报价不应比上一轮高", round).isLessThanOrEqualTo(previous);
                assertThat(quote).as("第 %d 轮报价不应低于底价", round).isGreaterThanOrEqualTo(floorFen);
                previous = quote;
            }
        }
    }

    @Test
    @DisplayName("样例表：真实底价 vs 攻击者反解出的底价")
    void 样例表() {
        Object[][] cases = {
                {1000_00L, 600_00L, 0},
                {1000_00L, 600_00L, 30},
                {1000_00L, 700_00L, 0},
                {800_00L, 500_00L, 0},
                {2500_00L, 2000_00L, 10},
                {123_45L, 100_00L, 3},
        };

        System.out.println();
        System.out.printf("%-11s %-11s %-5s %-11s | %-11s %-11s%n",
                "挂牌价", "真实底价", "上架", "第1轮报价", "反解(无扰动)", "反解(有扰动)");
        int productId = 1;
        for (Object[] c : cases) {
            long listFen = (Long) c[0];
            long floorFen = (Long) c[1];
            int days = (Integer) c[2];

            long plainQuote = quoteFen(noJitter, session(productId, 1L, listFen, floorFen, days), 1);
            long jitterQuote = quoteFen(jittered, session(productId, 1L, listFen, floorFen, days), 1);

            System.out.printf("%-11s %-11s %-5d %-11s | %-11s %-11s%n",
                    yuan(listFen), yuan(floorFen), days, yuan(jitterQuote),
                    yuan(recoverByAnchorOnly(listFen, plainQuote)),
                    yuan(recoverByAnchorOnly(listFen, jitterQuote)));
            productId++;
        }
        System.out.println();
    }

    private static String yuan(long fen) {
        return "¥" + BigDecimal.valueOf(fen, 2).toPlainString();
    }
}
