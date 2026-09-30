package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.negotiation.pricing.strategy.ComposedStrategy;
import com.example.unitrade.negotiation.pricing.strategy.FixedRatioStrategy;
import com.example.unitrade.negotiation.pricing.strategy.LadderStrategy;
import com.example.unitrade.negotiation.pricing.strategy.TimeDiscountStrategy;

import java.util.List;

/**
 * 内置策略清单 —— 装配点。
 *
 * <p>生产环境这一层由 Spring 配置类承担（把 {@code List<PricingStrategy>} 注入进来，
 * 因为每个策略都是 {@code @Component}）。这里手写一份，是为了让内核在没有 Spring
 * 上下文的情况下也能跑测试与评测 —— <b>"能脱离框架单测"本身就是分层是否干净的证据。</b>
 *
 * <p>要不要把三个候选策略也装进来？要。它们的作用是当<b>对照组</b>：
 * 评测报告里"生产策略 vs 固定比例"这一行，比任何文字论证都更有说服力。
 * 代价只是多几个 bean。
 */
public final class BuiltInStrategies {

    /** 生产环境应使用的策略名。 */
    public static final String PRODUCTION_STRATEGY = "composed";

    private BuiltInStrategies() {
    }

    /** 全部内置策略：1 个生产策略 + 3 个候选对照策略。 */
    public static List<PricingStrategy> all() {
        return List.of(
                new ComposedStrategy(),
                new FixedRatioStrategy(),
                new LadderStrategy(),
                new TimeDiscountStrategy());
    }

    /** 默认引擎：生产策略生效。 */
    public static PricingEngine defaultEngine() {
        return new PricingEngine(all(), PRODUCTION_STRATEGY);
    }
}
