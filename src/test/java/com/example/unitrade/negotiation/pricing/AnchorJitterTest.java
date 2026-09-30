package com.example.unitrade.negotiation.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锚点扰动派生器的纯函数测试。
 *
 * <p>它是防"底价被反解"的唯一依赖，所以三条性质都必须钉死：
 * <b>有界</b>（否则锚点可能超过 pMax，单调性崩）、
 * <b>确定性</b>（否则评测不可复现、买家刷新一次就有新价）、
 * <b>对输入敏感且分布均匀</b>（否则扰动退化成一个很小的固定偏移，防护形同虚设）。
 */
class AnchorJitterTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    @DisplayName("扰动始终落在 [−maxBp, +maxBp] 内")
    void 扰动有界() {
        int maxBp = 1500;
        for (int productId = 1; productId <= 3000; productId++) {
            int bp = AnchorJitter.of(SECRET, productId, 9L, maxBp);
            assertThat(bp).as("productId=%d", productId).isBetween(-maxBp, maxBp);
        }
    }

    @Test
    @DisplayName("同一入参永远同一结果（可复现），换任一入参都可能变")
    void 确定性且对输入敏感() {
        int a = AnchorJitter.of(SECRET, 42L, 9L, 1500);
        assertThat(AnchorJitter.of(SECRET, 42L, 9L, 1500)).isEqualTo(a);

        // 换密钥 / 换商品 / 换卖家，三者都不应该"几乎总是"撞到同一个值
        Set<Integer> variants = new HashSet<>();
        variants.add(a);
        variants.add(AnchorJitter.of(SECRET + "x", 42L, 9L, 1500));
        variants.add(AnchorJitter.of(SECRET, 43L, 9L, 1500));
        variants.add(AnchorJitter.of(SECRET, 42L, 10L, 1500));
        assertThat(variants).as("四个不同入参应该得到四个不同扰动").hasSize(4);
    }

    @Test
    @DisplayName("分布要够散：不能退化成少数几个值")
    void 分布够散() {
        Set<Integer> values = new HashSet<>();
        for (int productId = 1; productId <= 500; productId++) {
            values.add(AnchorJitter.of(SECRET, productId, 1L, 1500));
        }
        // 500 次抽样落在 3001 个格子里，撞车是正常的，但覆盖数量不该太少
        assertThat(values.size()).as("500 次抽样应覆盖足够多的取值").isGreaterThan(400);
    }

    @Test
    @DisplayName("maxBp = 0 时关闭扰动，恒返回 0")
    void 可关闭扰动() {
        assertThat(AnchorJitter.of(SECRET, 42L, 9L, 0)).isZero();
    }

    @Test
    @DisplayName("商品/卖家为 null 不应抛异常（防御性）")
    void 容忍空输入() {
        assertThat(AnchorJitter.of(SECRET, null, null, 1500)).isBetween(-1500, 1500);
    }

    @Test
    @DisplayName("扰动后再算锚点比例，仍然严格小于 pMax（单调性的前提）")
    void 扰动后锚点仍小于可让空间上限() {
        for (int productId = 1; productId <= 2000; productId++) {
            int bp = AnchorJitter.of(SECRET, productId, 1L, PriceFormulas.MAX_ANCHOR_JITTER_BP);
            for (int days : new int[]{0, 5, 30, 365}) {
                var anchor = PriceFormulas.anchorRatio(bp);
                assertThat(anchor)
                        .as("productId=%d days=%d", productId, days)
                        .isLessThan(PriceFormulas.pMax(days))
                        .isGreaterThan(java.math.BigDecimal.ZERO);
            }
        }
    }
}
