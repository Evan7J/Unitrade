package com.example.unitrade.negotiation.pricing.strategy;

import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.PricingStrategy;

import java.math.BigDecimal;

/**
 * 候选策略 ①：固定比例 —— 每轮让出固定的 10% 可让空间，不设上限。
 *
 * <pre>p(n) = 0.40 + 0.10·(n-1)</pre>
 *
 * <p><b>它的失败模式是「安全性」：</b>
 * <ul>
 *   <li>第 7 轮 p = 1.00 —— 报价正好等于底价，把卖家授权空间全部让光；</li>
 *   <li>第 8 轮起 p > 1 —— 算出的报价低于底价，靠引擎的安全网裁剪才没出事。</li>
 * </ul>
 * 换句话说：它把"不击穿底价"这件事<b>从数学保证降级成了运行期兜底</b>。
 * 这正是它被淘汰的原因，也是它被保留在代码库里的原因 ——
 * 没有这个反例，"为什么选几何衰减"就只是一句话。
 */
public final class FixedRatioStrategy implements PricingStrategy {

    /** 首轮锚定比例。与生产策略保持一致，这样对比时差异只来自"后续节奏"。 */
    public static final BigDecimal FIRST_RATIO = new BigDecimal("0.40");

    /** 每轮固定让步幅度。 */
    public static final BigDecimal STEP = new BigDecimal("0.10");

    /**
     * 从第几轮起 p ≥ 1（报价触底/越界）。
     * 解析可算：0.40 + 0.10·(n-1) ≥ 1 ⟹ n ≥ 7。不需要跑循环试探。
     */
    public static final int FIRST_BREACHING_ROUND = 7;

    @Override
    public String name() {
        return "fixed-ratio";
    }

    @Override
    public BigDecimal ratioAt(AuthorizedRange range, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("轮次从 1 开始，实际=" + round);
        }
        return FIRST_RATIO.add(STEP.multiply(BigDecimal.valueOf(round - 1L)));
    }
}
