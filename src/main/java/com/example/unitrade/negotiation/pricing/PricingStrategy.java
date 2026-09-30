package com.example.unitrade.negotiation.pricing;

import java.math.BigDecimal;

/**
 * 让步策略 SPI —— <b>只回答「该让出多少比例」，不回答「值多少钱」。</b>
 *
 * <p>这个接口的形状是被三条理由逼出来的，不是随手定的：
 * <ol>
 *   <li><b>比例与货币无关</b>，策略可以脱离价格、脱离业务上下文独立单测；
 *       如果返回价格，策略就得知道挂牌价、底价、取整规则，测试成本立刻上升。</li>
 *   <li><b>边界裁剪只写一遍</b>。如果策略返回价格，那"不得越界"这条规则
 *       要在每个策略里各写一遍 —— 三个策略就是三份可能写歪的重复代码。
 *       现在它只存在于 {@link PricingEngine} 一处。</li>
 *   <li><b>换货币单位/精度不影响策略</b>。将来从"分"改成"厘"，只动引擎。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code @FunctionalInterface}</b>：策略需要 {@link #name()} ——
 * 配置映射（application.yml 里写的就是这个名字）和评测报告都要用它，
 * 而函数式接口只允许一个抽象方法。
 * "lambda 写起来更短"不是选型理由，"够不够用"才是。
 *
 * <p>内置策略：
 * <ul>
 *   <li>{@code fixed-ratio} {@link FixedRatioStrategy} —— 固定比例（候选）</li>
 *   <li>{@code ladder} {@link LadderStrategy} —— 阶梯递减（候选）</li>
 *   <li>{@code time-discount} {@link TimeDiscountStrategy} —— 时间折扣（候选）</li>
 *   <li>{@code composed} {@link ComposedStrategy} —— 组合解析式（生产使用）</li>
 * </ul>
 * 三个候选策略是<b>横向替代</b>关系，用途是对比实验；生产策略是独立实现的解析式，
 * 不是把它们三个拼起来（见 {@link ComposedStrategy} 的类注释）。
 */
public interface PricingStrategy {

    /**
     * 策略名，同时是配置键与评测报告里的标识。要求全局唯一 ——
     * 重名会在引擎构造时直接抛异常（启动即失败，而不是运行到某条分支才发现）。
     */
    String name();

    /**
     * 第 {@code round} 轮「已让出的可让空间比例」p(n)，定义域 [0, 1) 表示尚未击穿底价。
     *
     * <p>返回 {@code ratio} 与轮次的关系完全由策略决定：
     * 有的看轮次（衰减类）、有的看时间（时间折扣）、有的两者都看（组合类）。
     * 返回值本身不含金额，也不含底价。
     *
     * @param range 授权区间（含底价，因为策略需要知道"可让空间"有多大）
     * @param round 轮次，从 1 开始
     */
    BigDecimal ratioAt(AuthorizedRange range, int round);
}
