package com.example.unitrade.negotiation.adapter;

import com.example.unitrade.entity.NegotiationSession;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.pricing.AnchorJitter;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.PriceFormulas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 议价内核与既有数据模型之间的适配层。
 *
 * <h2>为什么需要这一层</h2>
 * 两边的金额表示不一样，而且<b>都不该为对方让步</b>：
 * <ul>
 *   <li>既有表（t_product / t_order）用 {@code DECIMAL(10,2)} ↔ {@code BigDecimal}，
 *       这是历史决定的，改动它要动全站；</li>
 *   <li>议价内核用 {@code long}（分），因为钱的运算不能用浮点、也不该有 scale 歧义。</li>
 * </ul>
 * 与其让其中一方妥协，不如把转换集中到<b>一个</b>地方 ——
 * 转换逻辑分散在各处是 bug 的温床（有人漏了 scale、有人用了 double 中转）。
 *
 * <p>所以约定：<b>所有跨界的金额转换只能发生在这一层</b>，
 * 业务代码里不允许出现 {@code multiply(new BigDecimal(100))} 这样的手写换算。
 *
 * <h2>它还负责一件事：派生本次会话的锚点扰动</h2>
 * 首轮锚点比例不是一个全局常数，而是 {@code 基准 × (1 + 扰动)}，
 * 扰动由 {@link AnchorJitter} 从「商品 + 卖家 + 服务端密钥」用 HMAC 派生。
 * 这是为了让底价不能从首轮报价被精确反解（原因见 {@code PriceFormulas} 的类注释）。
 *
 * <p>放在适配层的原因是职责归属：内核只认 {@link AuthorizedRange}，
 * "这个会话是谁的商品、扰动该取多少"属于外部世界的知识，不该渗进 pricing 包。
 * 内核因此完全不感知"扰动"这个概念 —— 它只是拿到一个锚点并如实计算。
 */
@Component
public class NegotiationPricingAdapter {

    /** 开发用默认密钥。<b>生产必须通过环境变量覆盖</b> —— 它一旦泄露，扰动等于没有。 */
    public static final String DEFAULT_DEV_SECRET = "unitrade-dev-anchor-secret";

    private static final Logger log = LoggerFactory.getLogger(NegotiationPricingAdapter.class);

    private final BigDecimal baseAnchorRatio;
    private final int anchorJitterBp;
    private final String anchorJitterSecret;

    /**
     * 测试用构造：使用默认配置。
     *
     * <p>刻意保留无参构造，是为了让内核与适配层的单测不用起 Spring 上下文 ——
     * "能脱离 Spring 单测"是这个包的一条自我要求。
     */
    public NegotiationPricingAdapter() {
        this(PriceFormulas.BASE_ANCHOR_RATIO, PriceFormulas.MAX_ANCHOR_JITTER_BP, DEFAULT_DEV_SECRET);
    }

    @Autowired
    public NegotiationPricingAdapter(
            @Value("${negotiation.pricing.anchor-ratio:0.40}") BigDecimal baseAnchorRatio,
            @Value("${negotiation.pricing.anchor-jitter-bp:1500}") int anchorJitterBp,
            @Value("${negotiation.pricing.anchor-jitter-secret:" + DEFAULT_DEV_SECRET + "}")
            String anchorJitterSecret) {

        this.baseAnchorRatio = baseAnchorRatio;
        this.anchorJitterBp = anchorJitterBp;
        this.anchorJitterSecret = anchorJitterSecret;

        if (anchorJitterBp <= 0) {
            log.warn("[锚点] 扰动已关闭（anchor-jitter-bp={}）—— 底价将可以被首轮报价精确反解，"
                    + "只应在对照实验中这样配置", anchorJitterBp);
        }
        if (DEFAULT_DEV_SECRET.equals(anchorJitterSecret)) {
            log.warn("[锚点] 正在使用开发默认密钥 —— 上线前必须通过 "
                    + "ANCHOR_JITTER_SECRET 环境变量覆盖，否则扰动可被离线复现");
        }
        log.info("[锚点] 基准比例={} 扰动幅度=±{}bp", baseAnchorRatio, Math.abs(anchorJitterBp));
    }

    /**
     * 会话快照 → 内核授权区间。
     *
     * <p>注意用的是 {@code snapshot*} 字段而不是商品的当前值：
     * 会话一旦创建，它谈的就是那一刻的授权区间。卖家中途改价不影响进行中的会话。
     */
    public AuthorizedRange toRange(NegotiationSession session) {
        int daysListed = session.getSnapshotDaysListed() == null ? 0 : session.getSnapshotDaysListed();
        return new AuthorizedRange(
                Money.ofYuan(session.getSnapshotListPrice()),
                Money.ofYuan(session.getSnapshotFloorPrice()),
                daysListed,
                anchorRatioOf(session));
    }

    /**
     * 本次会话的锚点比例 = 基准 × (1 + 扰动)。
     *
     * <p>扰动按「商品 + 卖家」派生而不是按会话 —— 这个选择很关键：
     * 按会话派生的话，攻击者对同一件商品多开几个会话就能拿到多组样本、取极值反推 h，
     * 防护会失效。按商品派生则重复观察不产生新信息。
     */
    BigDecimal anchorRatioOf(NegotiationSession session) {
        int jitterBp = AnchorJitter.of(
                anchorJitterSecret, session.getProductId(), session.getSellerId(), anchorJitterBp);
        return PriceFormulas.anchorRatio(baseAnchorRatio, jitterBp);
    }

    /** BigDecimal（元）→ Money（分）。 */
    public Money toMoney(BigDecimal yuan) {
        return Money.ofYuan(yuan);
    }

    /** Money（分）→ BigDecimal（元）。 */
    public BigDecimal toYuan(Money money) {
        return money.toYuan();
    }
}
