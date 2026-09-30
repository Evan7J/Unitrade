package com.example.unitrade.negotiation.adapter;

import com.example.unitrade.entity.NegotiationSession;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import com.example.unitrade.negotiation.pricing.PricingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「会话快照 → 授权区间 → 引擎报价」这条链路的回归测试。
 *
 * <h2>为什么需要它（起因是一次没能复现的异常）</h2>
 * 实测中曾出现过一次：同样 list=1000 / floor=700，首轮报价却是 900（应为 880）。
 * 事后用同一个商品连跑三次都是 880，库里也找不到任何能产生 900 的底价 ——
 * <b>没能复现，但它是钱相关的路径，不能就这么放过去</b>。
 *
 * <p>定价引擎本身是纯函数，已有属性测试覆盖，所以嫌疑只可能在<b>输入</b>上：
 * 快照字段取错、单位换算错、天数取到脏值、锚点扰动算错。这条链路正好是四者交汇的地方，
 * 于是把它显式钉死 —— 以后谁改了这里，测试会立刻报出来。
 *
 * <h2>⚠️ 关于「首轮锚定」的断言在 2026-09-24 变了</h2>
 * 原来这里断言"首轮报价恒为 880.00"。那个断言现在<b>故意不再成立</b>：
 * 锚点比例从固定常数改成了 {@code 基准 × (1 + 会话扰动)}，
 * 因为固定常数会让底价被一行算式精确反解（见 {@code FloorPriceLeakTest}）。
 *
 * <p>所以断言拆成了三条，合起来比原来更强：
 * <ol>
 *   <li>锚点<b>落在</b> {@code [基准×(1−幅度), 基准×(1+幅度)]} 内 —— 界住了扰动；</li>
 *   <li>同一商品 + 同一卖家<b>永远同一个值</b> —— 界住了可复现性；</li>
 *   <li>基准锚点（三参构造）仍然精确等于 0.40 —— 内核本身没变。</li>
 * </ol>
 */
class NegotiationPricingAdapterTest {

    private final NegotiationPricingAdapter adapter = new NegotiationPricingAdapter();
    private final PricingEngine engine = BuiltInStrategies.defaultEngine();

    private static NegotiationSession session(BigDecimal list, BigDecimal floor, int days) {
        NegotiationSession s = new NegotiationSession();
        s.setProductId(7L);
        s.setSellerId(3L);
        s.setSnapshotListPrice(list);
        s.setSnapshotFloorPrice(floor);
        s.setSnapshotDaysListed(days);
        return s;
    }

    private BigDecimal firstQuote(NegotiationSession s) {
        return engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, adapter.toRange(s), 1)
                .price().toYuan();
    }

    @Test
    @DisplayName("快照 → 授权区间：用的必须是快照字段，单位换算到分")
    void 快照转授权区间() {
        AuthorizedRange range = adapter.toRange(session(new BigDecimal("1000.00"), new BigDecimal("700.00"), 0));

        assertThat(range.listPrice().fen()).isEqualTo(100_000L);
        assertThat(range.floorPrice().fen()).isEqualTo(70_000L);
        assertThat(range.headroom().fen()).isEqualTo(30_000L);
        assertThat(range.daysListed()).isZero();
    }

    @Test
    @DisplayName("天数为空时按 0 处理，不能让 null 流进时间折扣")
    void 天数为空按零处理() {
        NegotiationSession s = session(new BigDecimal("800.00"), new BigDecimal("500.00"), 0);
        s.setSnapshotDaysListed(null);

        assertThat(adapter.toRange(s).daysListed()).isZero();
    }

    @Test
    @DisplayName("首轮锚点被扰动界住：落在基准 ±15% 之内，绝不超过可让空间上限")
    void 首轮锚点被扰动界住() {
        BigDecimal headroom = new BigDecimal("300.00");
        BigDecimal list = new BigDecimal("1000.00");
        BigDecimal lowerBound = new BigDecimal("0.34");   // 0.40 × 0.85
        BigDecimal upperBound = new BigDecimal("0.46");   // 0.40 × 1.15

        // 覆盖若干不同商品，观察扰动确实在动，但始终在范围内
        BigDecimal minAnchor = BigDecimal.ONE;
        BigDecimal maxAnchor = BigDecimal.ZERO;

        for (long productId = 1; productId <= 60; productId++) {
            NegotiationSession s = session(list, list.subtract(headroom), 0);
            s.setProductId(productId);

            BigDecimal quote = firstQuote(s);
            BigDecimal anchor = list.subtract(quote).divide(headroom, 6, java.math.RoundingMode.HALF_UP);

            assertThat(anchor)
                    .as("productId=%d 的锚点 %.4f 超出了扰动范围", productId, anchor)
                    .isBetween(lowerBound, upperBound);

            minAnchor = minAnchor.min(anchor);
            maxAnchor = maxAnchor.max(anchor);
        }

        assertThat(maxAnchor.subtract(minAnchor))
                .as("扰动必须真的在动 —— 否则等于没加防护")
                .isGreaterThan(new BigDecimal("0.02"));
    }

    @Test
    @DisplayName("同一商品 + 同一卖家：锚点必须可复现（否则买家刷新就有新价）")
    void 锚点按商品可复现() {
        NegotiationSession a = session(new BigDecimal("1000.00"), new BigDecimal("700.00"), 0);
        NegotiationSession b = session(new BigDecimal("1000.00"), new BigDecimal("700.00"), 0);

        assertThat(firstQuote(a)).isEqualByComparingTo(firstQuote(b));
    }

    @Test
    @DisplayName("首轮锚点不随上架天数漂移 —— 时间折扣只影响上限，不污染锚点")
    void 首轮锚定不受上架天数影响() {
        BigDecimal firstDay0 = null;
        for (int days : new int[]{0, 1, 10, 30, 90}) {
            BigDecimal quote = firstQuote(session(new BigDecimal("1000.00"), new BigDecimal("700.00"), days));

            if (firstDay0 == null) {
                firstDay0 = quote;
            }
            assertThat(quote)
                    .as("第 1 轮报价不应随上架天数变化（days=%d）", days)
                    .isEqualByComparingTo(firstDay0);
        }
    }

    @Test
    @DisplayName("内核基准锚点仍然精确等于 0.40（三参构造不参与会话扰动）")
    void 内核基准锚点精确等于四成() {
        AuthorizedRange range = new AuthorizedRange(
                Money.ofYuan(new BigDecimal("1000.00")),
                Money.ofYuan(new BigDecimal("700.00")),
                0);

        assertThat(range.anchorRatio()).isEqualByComparingTo(PriceFormulas.BASE_ANCHOR_RATIO);
        assertThat(engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, 1).price().toYuan())
                .as("挂牌 1000 / 底价 700 → 1000 − 40%%×300 = 880")
                .isEqualByComparingTo(new BigDecimal("880.00"));
    }

    @Test
    @DisplayName("多轮报价单调非增，且始终高于底价")
    void 多轮单调非增且不击穿底价() {
        AuthorizedRange range = adapter.toRange(
                session(new BigDecimal("1000.00"), new BigDecimal("700.00"), 0));

        BigDecimal previous = null;
        for (int round = 1; round <= 30; round++) {
            BigDecimal quote = engine.quoteWith(BuiltInStrategies.PRODUCTION_STRATEGY, range, round)
                    .price().toYuan();

            if (previous != null) {
                assertThat(quote).as("第 %d 轮报价不应高于上一轮", round)
                        .isLessThanOrEqualTo(previous);
            }
            assertThat(quote).as("第 %d 轮报价不应低于底价", round)
                    .isGreaterThanOrEqualTo(new BigDecimal("700.00"));
            previous = quote;
        }
    }
}
