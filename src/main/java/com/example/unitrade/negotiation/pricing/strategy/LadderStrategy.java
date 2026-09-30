package com.example.unitrade.negotiation.pricing.strategy;

import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.PricingStrategy;

import java.math.BigDecimal;

/**
 * 候选策略 ②：阶梯递减 —— 人为把让步节奏分成若干档，到顶后原地踏步。
 *
 * <p><b>它的失败模式是「适应性」：</b>
 * 它<b>完全不看上架天数</b>。同一个商品挂 1 天和挂 30 天，报价序列一模一样。
 * 一个挂了 30 天、卖家急着出手的商品，会被它拖成卖不掉的僵尸链接。
 *
 * <p>这个缺陷在代码 review 里几乎看不出来——阶梯表本身写得很"整齐"，
 * 逻辑也没有 bug。它只有在"同一个商品跑两组 days 对比"时才现形。
 * 这就是为什么评测要做<b>对照实验</b>，而不是只看一条曲线像不像样。
 *
 * <p>另外它到顶后不动，需要靠 {@code StallDetector} 兜底转人工，
 * 否则会无限输出同一个价格 —— 属于"策略没覆盖到"的状态。
 */
public final class LadderStrategy implements PricingStrategy {

    /**
     * 阶梯表：第 n 轮取第 n 档，超出则取最后一档。
     * 数值刻意设计成"前期快、后期慢"，看起来比固定比例更像人谈判。
     */
    private static final BigDecimal[] LADDER = {
            new BigDecimal("0.40"), new BigDecimal("0.52"), new BigDecimal("0.61"),
            new BigDecimal("0.68"), new BigDecimal("0.73"), new BigDecimal("0.76"),
            new BigDecimal("0.78"), new BigDecimal("0.79"), new BigDecimal("0.80")
    };

    @Override
    public String name() {
        return "ladder";
    }

    @Override
    public BigDecimal ratioAt(AuthorizedRange range, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("轮次从 1 开始，实际=" + round);
        }
        // 注意 range 参数完全没被使用 —— 这正是本策略的缺陷所在，
        // 把"没用上"写成显式的事实，比藏起来更容易在 review 时被发现。
        int idx = Math.min(round - 1, LADDER.length - 1);
        return LADDER[idx];
    }

    /** 阶梯到顶的轮次：之后报价不再变化 → 触发僵局检测。 */
    public static int plateauRound() {
        return LADDER.length;
    }
}
