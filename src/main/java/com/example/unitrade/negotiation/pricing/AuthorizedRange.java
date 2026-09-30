package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.negotiation.domain.ItemSnapshot;
import com.example.unitrade.negotiation.domain.Money;

import java.math.BigDecimal;

/**
 * 卖家授权区间 —— <b>底价在整个系统里唯一的物理位置。</b>
 *
 * <p>这个类型被刻意放在 {@code pricing} 包内，并由架构测试
 * （{@code FloorPriceArchitectureTest}）保证它不被任何包外代码依赖。
 * 也就是说：底价的可见范围 = 定价包，越界即 CI 失败。
 *
 * <p>为什么这是架构约束而不是编码纪律：纪律依赖"记得别写漏"，
 * 而人会忘、新人会不知道、重构会破坏。把规则写成可执行的测试之后，
 * 它就和"编译通过"一样是硬性的。
 *
 * @param listPrice   挂牌价（买家本来就看得到）
 * @param floorPrice  卖家底价（授权下限，绝不对外）
 * @param daysListed  上架天数（时间折扣的输入，由外部传入，不读时钟）
 * @param anchorRatio 本次会话的首轮锚点比例 a = 基准 × (1 + 扰动)。
 *                    ⚠️ 它和底价一样<b>不能对外</b>：攻击者一旦知道 a，
 *                    用 {@code h = 首轮让出额 / a} 就能精确反解底价，
 *                    所以 {@link #toString()} 把它一并屏蔽了。
 */
public record AuthorizedRange(Money listPrice, Money floorPrice, int daysListed,
                              BigDecimal anchorRatio) {

    public AuthorizedRange {
        if (listPrice == null || floorPrice == null) {
            throw new IllegalArgumentException("价格不能为 null");
        }
        if (listPrice.fen() <= 0) {
            throw new IllegalArgumentException("挂牌价必须为正，实际=" + listPrice.display());
        }
        if (floorPrice.fen() <= 0) {
            throw new IllegalArgumentException("底价必须为正，实际=" + floorPrice.display());
        }
        if (floorPrice.fen() > listPrice.fen()) {
            throw new IllegalArgumentException(
                    "底价不能高于挂牌价：底价=" + floorPrice.display() + " 挂牌=" + listPrice.display());
        }
        if (daysListed < 0) {
            throw new IllegalArgumentException("上架天数不能为负，实际=" + daysListed);
        }
        if (anchorRatio == null || anchorRatio.signum() <= 0) {
            throw new IllegalArgumentException("锚点比例必须为正，实际=" + anchorRatio);
        }
        // 这条守卫是"单调性"的物理边界：a ≥ pMax 会让 q ≤ 0，报价曲线不再往下走。
        // 放在类型里而不是公式里，是为了让配置写错时在构造对象那一刻就炸，
        // 而不是等到某次报价算出一个比上一轮更高的价格。
        BigDecimal pMax = PriceFormulas.pMax(daysListed);
        if (anchorRatio.compareTo(pMax) >= 0) {
            throw new IllegalArgumentException(
                    "锚点比例 " + anchorRatio + " 必须严格小于可让空间上限 " + pMax
                            + "，否则衰减比 q ≤ 0，报价不再单调递减");
        }
    }

    /**
     * 便捷构造：使用基准锚点（无扰动）。
     *
     * <p>保留它是为了不打扰两类调用方：定价内核的历史测试，以及策略对照实验 ——
     * 它们关心的是"公式本身对不对"，不应该被会话扰动干扰。
     * 生产路径（{@code NegotiationPricingAdapter}）一律走四参数版本。
     */
    public AuthorizedRange(Money listPrice, Money floorPrice, int daysListed) {
        this(listPrice, floorPrice, daysListed, PriceFormulas.BASE_ANCHOR_RATIO);
    }

    /**
     * 由「公开的商品事实」+「卖家私有的底价」组合出授权区间。
     *
     * <p>这个工厂方法是两条数据流的汇合点，也是底价进入系统的唯一入口。
     * 把它做成显式方法而不是散落的构造调用，是为了让"底价从哪来"这个问题
     * 在代码里只有一个答案（来自卖家配置表，不来自商品表）。
     *
     * <p>注意它用的是基准锚点 —— 这个方法服务于内核的内部组合，
     * 不参与会话级扰动。会话级锚点由适配层注入。
     */
    public static AuthorizedRange of(ItemSnapshot item, Money floorPrice) {
        return new AuthorizedRange(item.listPrice(), floorPrice, item.daysListed());
    }

    /** 可让空间 h = 挂牌价 - 底价。含底价信息，因此同样不出 pricing 包。 */
    public Money headroom() {
        return listPrice.minus(floorPrice);
    }

    /**
     * 是否具备议价空间。
     *
     * <p>为什么需要这个方法：h = 1 分时，首轮让出额 0.4 分被取整吃掉，
     * 第 2 轮起报价完全不动 —— 这不是"谈判僵持"，是"这个商品根本不该进议价流程"。
     * 属性测试的反例缩小功能把这类输入缩到了 h=1 分，进而暴露出这条规则。
     */
    public boolean isNegotiable() {
        return headroom().fen() > 0;
    }

    /**
     * 覆写 toString 来屏蔽底价<b>和锚点比例</b> —— record 默认会打印所有字段。
     *
     * <p>为什么锚点也要屏蔽：它不是敏感字段本身，但它和公开的首轮报价联立后
     * 可以解出 h（这正好是构建期立的判据 ——「联立后能否解出敏感量」）。
     *
     * <p>注意这个防护的定位：它是「纵深防御」的最后一层，
     * 针对的是"有人不小心把 AuthorizedRange 直接 log 出来"这种事故。
     * 它不能替代架构测试（架构测试防的是它能被谁引用），两者互补。
     */
    @Override
    public String toString() {
        return "AuthorizedRange[listPrice=" + listPrice.display()
                + ", floorPrice=<redacted>, daysListed=" + daysListed
                + ", anchorRatio=<redacted>]";
    }
}
