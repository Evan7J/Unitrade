package com.example.unitrade.negotiation.pricing.strategy;

import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import com.example.unitrade.negotiation.pricing.PricingStrategy;

import java.math.BigDecimal;

/**
 * 候选策略 ③：时间折扣 —— 一步到位，直接按卖家急迫度报出可让空间上限。
 *
 * <pre>p(n) = pMax(d)，与轮次无关</pre>
 *
 * <p><b>它的失败模式是「博弈性」：</b>
 * 首轮就把 80%（冷启动商品）/ 95%（急售商品）的空间全让出去，
 * 第 2 轮起报价完全不动 —— 谈判节奏消失。
 *
 * <p>它的数字很"安全"（永不越界、安全垫固定），如果只看安全指标，
 * 它甚至比生产策略更好看。这提醒一件重要的事：
 * <b>安全性指标好看不等于策略可用</b>，必须同时看"有没有议价过程"。
 *
 * <p>对买家的实际后果：第一句话就能拿到最低价。
 * 买家会得出"这卖家没有底线、还能再压"的结论，反而更容易谈崩；
 * 而且它把「卖家急售」这个私有信息，用第一句话直接告诉了买家。
 */
public final class TimeDiscountStrategy implements PricingStrategy {

    @Override
    public String name() {
        return "time-discount";
    }

    @Override
    public BigDecimal ratioAt(AuthorizedRange range, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("轮次从 1 开始，实际=" + round);
        }
        // 同样不读 round —— 与 LadderStrategy 相反，它只看时间不看节奏。
        return PriceFormulas.pMax(range.daysListed());
    }
}
