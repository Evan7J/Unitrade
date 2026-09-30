package com.example.unitrade.negotiation.pricing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 定价公式的解析量 —— 纯数学，零依赖，可独立单测。
 *
 * <p>令 d = min(上架天数, 30)，a = 本次会话的锚点比例：
 * <pre>
 *   pMax = 0.80 + (0.95-0.80)·d/30 = (160 + d) / 200         可让空间上限   ∈ [0.80, 0.95]
 *   q    = 1 - a/pMax                                         逐轮衰减比     ∈ (0, 1)
 *   A    = pMax - a                                           首轮让步额占比
 *   p(n) = pMax - A·q^(n-1)  = pMax·(1 - q^n)                 第 n 轮已让出比例
 * </pre>
 *
 * <p>三个可解析证明的性质（不是断言，是证明）：
 * <ol>
 *   <li>{@code p(1) = pMax - A = a} —— 首轮锚定恒等于会话锚点，与上架天数无关。
 *       锚点是买家看到的第一个价格，它必须是稳定信号，不能被时间折扣污染。</li>
 *   <li>{@code 0 < q < 1} ⟹ p(n) 严格递增 ⟹ 报价严格递减。
 *       （{@code q > 0} 等价于 {@code a < pMax}，这是构造函数里守卫的那条不等式。）</li>
 *   <li>{@code p(n) → pMax ≤ 0.95 < 1} ⟹ 报价收敛到底价之上，恒有一块正安全垫。
 *       安全垫是几何级数收敛性的副产品，不是 {@code if (price < floor)} 判出来的 ——
 *       这是选几何衰减而不是线性递减的主要理由。</li>
 * </ol>
 *
 * <h2>⚠️ 锚点为什么不再是固定常数（2026-09-24 修正）</h2>
 * 原实现把首轮锚点硬编码为 {@code 0.40}。它满足上面第 1 条，但有一个严重副作用：
 * <b>锚点变成一个公开、可观测的常数，于是底价可以被精确反解</b>：
 * <pre>
 *   底价 = 挂牌价 - (挂牌价 - 首轮报价) / 0.40
 * </pre>
 * 实测（{@code FloorPriceLeakTest}）：随机 2000 组，反解误差 ≤ 2 分 —— 等于没有保密。
 *
 * <p>所以锚点改成 {@code a = 基准比例 × (1 + 会话扰动)}，扰动由
 * {@link AnchorJitter} 用 HMAC 从「商品 + 卖家 + 服务端密钥」派生：
 * <ul>
 *   <li><b>扰动对攻击者不可知</b>（密钥在服务端），所以上面的反解不再精确；</li>
 *   <li><b>同一商品 + 同一卖家永远同一个扰动</b> —— 这一点比"按会话随机"更重要：
 *       否则攻击者只要多开几个会话、对同一件商品取极值，又能把 h 解出来；</li>
 *   <li><b>会话内完全可复现</b>：扰动是确定性派生，评测仍然可复现。</li>
 * </ul>
 * 泄漏的残余上界 = 扰动幅度（默认 ±15% 的可让空间），例如 h = ¥400 时约 ±¥60。
 *
 * <p><b>为什么全部用精确有理式，而不是先算 a/pMax 再乘</b>：
 * 那样 p(1) 会因为中间除法的取整误差被污染 1 分（开发中真实踩到过：
 * 某组输入的首轮锚点变成 40.02%）。现在 p(1) 走的是 pMax - A 这条路径，
 * 完全不经过 q，所以精度误差进不来。
 */
public final class PriceFormulas {

    /**
     * 首轮锚定基准：默认让出可让空间的 40%。
     *
     * <p>这个值可以通过 {@code negotiation.pricing.anchor-ratio} 覆盖，因为它是一个
     * <b>产品参数而不是数学结论</b>：
     * <ul>
     *   <li>调大（如 0.40）→ 首轮让得多 → <b>促成交易优先</b>；</li>
     *   <li>调小（如 0.25）→ 首轮让得少 → <b>守住价格优先</b>，但 5 轮内用不满授权空间。</li>
     * </ul>
     * 约束只有一条：必须严格小于 {@code pMax}（否则 q ≤ 0，单调性崩），
     * 这一点由 {@link AuthorizedRange} 的构造函数守卫。
     */
    public static final BigDecimal BASE_ANCHOR_RATIO = new BigDecimal("0.40");

    /** 兼容旧名，语义等同于 {@link #BASE_ANCHOR_RATIO}。 */
    public static final BigDecimal FIRST_RATIO = BASE_ANCHOR_RATIO;

    /**
     * 锚点扰动的最大幅度，单位 basis point（1bp = 0.01%）。1500bp = ±15%。
     *
     * <p>幅度就是泄漏的残余上界：反解出的底价误差 ≤ 可让空间 × 该幅度。
     * 调大会让首轮报价在不同商品间差异更明显（可能是缺点），调小则更容易被反解。
     * 设为 0 等于关闭防护 —— 只应该用于对照实验。
     */
    public static final int MAX_ANCHOR_JITTER_BP = 1500;

    /** 时间折扣下界：卖家不急（刚上架）时的可让空间上限比例。 */
    public static final BigDecimal P_MAX_COLD = new BigDecimal("0.80");

    /** 时间折扣上界：卖家最急时的可让空间上限比例。仍 < 1，这是"不击穿底价"的解析保证。 */
    public static final BigDecimal P_MAX_HOT = new BigDecimal("0.95");

    /** 上架多少天后时间折扣封顶。 */
    public static final int URGENCY_CAP_DAYS = 30;

    /** q 的精度。30 位够用；再多只会让 pow() 越来越慢而没有任何业务收益。 */
    private static final int DECAY_SCALE = 30;

    /**
     * 幂运算的精度上限（有效数字位数）。
     *
     * <p><b>为什么必须显式给 MathContext</b>：{@code BigDecimal.pow(n)} 的 scale 是
     * 累乘的（scale × n）—— q 有 30 位小数，那 q³⁰⁰ 就是 9000 位小数，
     * 每次乘方都在做 9000 位精度的运算。写测试时这个坑会以"跑不动、超时被 kill"
     * 的形式出现，很容易被误判成"测试写太多了"。
     *
     * <p>用 MathContext 把有效数字钉在 40 位之后，pow 的代价就与轮次无关了。
     * 这也是"精度要按业务需求给，不是越多越好"的一个具体例子：
     * 业务上第 20 轮就该转人工了，算到 9000 位没有任何意义。
     */
    private static final MathContext POWER_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);

    private static final BigDecimal DENOM_200 = BigDecimal.valueOf(200);

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private PriceFormulas() {
    }

    /** d = min(上架天数, 30)。时间折扣在此封顶，挂一年的商品不会比挂 30 天的更急。 */
    public static int cappedDays(int daysListed) {
        return Math.min(Math.max(daysListed, 0), URGENCY_CAP_DAYS);
    }

    /** 卖家急迫度 α = d/30 ∈ [0,1]。仅用于展示与日志。 */
    public static BigDecimal urgency(int daysListed) {
        return BigDecimal.valueOf(cappedDays(daysListed))
                .divide(BigDecimal.valueOf(URGENCY_CAP_DAYS), 6, RoundingMode.HALF_UP);
    }

    /**
     * pMax = (160 + d) / 200 ∈ [0.80, 0.95]。
     *
     * <p>用 {@link RoundingMode#UNNECESSARY} 而不是默认行为：
     * 分母 200 = 2³·5²，任何整数/200 都能精确表示到 3 位小数，
     * 所以这里本来就不该发生任何舍入。如果哪天有人改了分母导致需要舍入，
     * 这行会当场抛异常，而不是悄悄吞掉误差 —— 这比多加一个测试更可靠。
     */
    public static BigDecimal pMax(int daysListed) {
        return BigDecimal.valueOf(160L + cappedDays(daysListed))
                .divide(DENOM_200, 3, RoundingMode.UNNECESSARY);
    }

    /**
     * 会话锚点 a = 基准比例 × (1 + 扰动)。
     *
     * <p>扰动用 bp 表示（1500 = +15%）。除以 10000 是有限小数，
     * 所以整个过程<b>没有任何舍入</b>：a 始终是一个精确十进制数，
     * 这让 {@code p(1) = a} 这条恒等式可以精确成立（属性测试会断言它）。
     */
    public static BigDecimal anchorRatio(BigDecimal baseRatio, int jitterBp) {
        if (baseRatio == null || baseRatio.signum() <= 0) {
            throw new IllegalArgumentException("锚点基准比例必须为正，实际=" + baseRatio);
        }
        BigDecimal jitter = BigDecimal.valueOf(jitterBp)
                .divide(TEN_THOUSAND, 4, RoundingMode.UNNECESSARY);
        BigDecimal ratio = baseRatio.multiply(BigDecimal.ONE.add(jitter));
        if (ratio.signum() <= 0) {
            throw new IllegalArgumentException("扰动后锚点比例必须为正，实际=" + ratio + "（jitterBp=" + jitterBp + "）");
        }
        return ratio;
    }

    /** 使用基准比例的锚点。 */
    public static BigDecimal anchorRatio(int jitterBp) {
        return anchorRatio(BASE_ANCHOR_RATIO, jitterBp);
    }

    /**
     * q = 1 - a/pMax ∈ (0, 1)。这个除法不精确，所以要显式给精度。
     *
     * <p>它是单调性的唯一依赖：{@code q ∈ (0,1)} ⟺ {@code 0 < a < pMax}。
     * 所以 {@code a ≥ pMax} 必须快速失败，而不是算出一条会往上涨的报价曲线。
     */
    public static BigDecimal decayRatio(int daysListed, BigDecimal anchorRatio) {
        BigDecimal pMax = pMax(daysListed);
        if (anchorRatio.compareTo(pMax) >= 0) {
            throw new IllegalArgumentException(
                    "锚点比例 " + anchorRatio + " 必须严格小于可让空间上限 " + pMax
                            + "，否则衰减比 q ≤ 0，报价不再单调递减");
        }
        return BigDecimal.ONE.subtract(anchorRatio.divide(pMax, DECAY_SCALE, RoundingMode.HALF_UP));
    }

    /** A = pMax - a。首轮让步额占比。减法精确，不需要舍入。 */
    public static BigDecimal anchorStep(int daysListed, BigDecimal anchorRatio) {
        return pMax(daysListed).subtract(anchorRatio);
    }

    /**
     * p(n) = pMax - A·q^(n-1)，第 n 轮"已让出的可让空间比例"。
     *
     * <p>n=1 时指数为 0，{@code q^0 = 1} 精确，所以 {@code p(1) = pMax - (pMax - a) = a}
     * 完全不经过 q 的精度 —— 这是锚点可以被精确断言的实现基础。
     */
    public static BigDecimal ratioAt(int daysListed, BigDecimal anchorRatio, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("轮次从 1 开始，实际=" + round);
        }
        BigDecimal decayed = decayRatio(daysListed, anchorRatio).pow(round - 1, POWER_CONTEXT);
        return pMax(daysListed).subtract(anchorStep(daysListed, anchorRatio).multiply(decayed));
    }

    // ==================================================================
    // 兼容旧签名 —— 一律使用基准锚点（无扰动）。
    // 保留它们是为了让定价内核的历史测试与对照实验保持原样：
    // 那些测试关心的是"公式本身对不对"，不应该被会话扰动干扰。
    // ==================================================================

    /** @see #decayRatio(int, BigDecimal) */
    public static BigDecimal decayRatio(int daysListed) {
        return decayRatio(daysListed, BASE_ANCHOR_RATIO);
    }

    /** @see #anchorStep(int, BigDecimal) */
    public static BigDecimal anchorStep(int daysListed) {
        return anchorStep(daysListed, BASE_ANCHOR_RATIO);
    }

    /** @see #ratioAt(int, BigDecimal, int) */
    public static BigDecimal ratioAt(int daysListed, int round) {
        return ratioAt(daysListed, BASE_ANCHOR_RATIO, round);
    }
}
