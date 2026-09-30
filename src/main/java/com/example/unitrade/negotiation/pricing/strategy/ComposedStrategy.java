package com.example.unitrade.negotiation.pricing.strategy;

import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import com.example.unitrade.negotiation.pricing.PricingStrategy;

import java.math.BigDecimal;

/**
 * 生产策略：组合式解析解 —— 首轮锚定 + 逐轮几何衰减 + 时间折扣。
 *
 * <pre>
 *   d    = min(上架天数, 30)
 *   pMax = (160 + d) / 200
 *   q    = (80 + d) / (160 + d)
 *   A    = (80 + d) / 200
 *   p(n) = pMax - A·q^(n-1)
 * </pre>
 *
 * <h2>关键取舍：「组合」这个词有两种含义，只有一种是对的</h2>
 *
 * <p><b>❌ 拼装式</b>：{@code p(n) = 固定步长(n) + 时间折扣(d)}
 * <ul>
 *   <li>每个策略只管自己那一份，<b>没有任何组件对"序列整体是否单调"负责</b> ——
 *       责任被拆散了，就等于没人负责；</li>
 *   <li>单调性要逐个组合去验证，<b>验证量随策略数增长</b>（3 个策略要验 3 组，
 *       4 个就要验 6 组）；</li>
 *   <li>它把"可证明"退化成了"靠测试去撞"。</li>
 * </ul>
 *
 * <p><b>✅ 合并式</b>（本类采用）：把三个正交因子（起点 / 节奏 / 斜率）
 * 合并成<b>一个</b>解析式，由一个组件对整条序列负责。于是：
 * <ul>
 *   <li>单调性 = <b>一个不等式</b> {@code 0 < q < 1}（等价于 {@code 0 < a < pMax}）；</li>
 *   <li>不击穿底价 = {@code pMax < 1}，解析可证；</li>
 *   <li>首轮锚定 = {@code p(1) = pMax - A = a}，恒等式，与 d 无关。</li>
 * </ul>
 *
 * <p>⚠️ 注意 {@code a}（首轮锚点比例）<b>不是全局常数</b>：
 * 它 = 基准比例（默认 0.40，可配置）× (1 + 会话扰动)，由适配层注入到
 * {@link AuthorizedRange#anchorRatio()}。把它做成常数会让底价被一行算式精确反解 ——
 * 详见 {@code PriceFormulas} 的类注释与 {@code FloorPriceLeakTest}。
 *
 * <p>所以本类是"第四个候选策略"，<b>不是"前三个的组合器"</b>。
 * 一句话记住：<b>策略模式用于「横向替代」，不用于「纵向拼装」。</b>
 *
 * <p>它相对三个候选策略的表现（真实测量值见 {@code StrategyComparisonTest}）：
 * 越界 0 次、首轮恒 40%、安全垫恒为正、且报价随上架天数变化（保留适应性）。
 */
public final class ComposedStrategy implements PricingStrategy {

    @Override
    public String name() {
        return "composed";
    }

    @Override
    public BigDecimal ratioAt(AuthorizedRange range, int round) {
        // 锚点比例来自 range（= 基准 × 会话扰动），不是全局常数 ——
        // 原因见 PriceFormulas 类注释：固定锚点会让底价被精确反解。
        return PriceFormulas.ratioAt(range.daysListed(), range.anchorRatio(), round);
    }
}
