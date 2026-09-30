package com.example.unitrade.negotiation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额。内部一律以「分」为单位存储，禁止用 double / float。
 *
 * <p>为什么是 record 而不是 class：值语义。两个 Money 相等就是金额相等，
 * 没有标识、没有生命周期，record 天然给了 equals / hashCode / toString。
 *
 * <p>为什么不用 double：0.1 + 0.2 != 0.3，且 1e16 + 1 == 1e16（小数被吞）。
 * 货币场景要的是「平」，不是「误差很小」。详见讲义第 2 讲 MoneyDemo。
 *
 * <p>为什么用 {@link Math#addExact} 而不是 {@code +}：
 * 溢出时抛 ArithmeticException 而不是静默回绕成负数。
 * 一个变成负数的价格如果被当成正常值走到话术里，比直接崩溃危险得多。
 *
 * <p>注意：本类型只表达「多少钱」，不表达「这是不是底价」。
 * 底价的保密性由 {@code pricing.AuthorizedRange} 的类型边界负责（见 ArchUnit 测试）。
 */
public record Money(long fen) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    /** 元 → 分。仅用于测试与构造入参，业务链路中不出现。 */
    public static Money ofYuan(long yuan) {
        return new Money(Math.multiplyExact(yuan, 100L));
    }

    /**
     * 元 → 分，接受字符串以避免浮点误差。
     * {@code "600.50"} → 60050 分。
     *
     * <p>用 {@link RoundingMode#UNNECESSARY} 是刻意的：超过两位小数的金额
     * （比如 "600.555"）必须当场抛异常，而不是悄悄舍入。
     * 这是"入参不合法就早失败"原则在金额上的体现。
     */
    public static Money ofYuan(String yuan) {
        return new Money(new BigDecimal(yuan)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact());
    }

    /**
     * BigDecimal（元）→ Money（分）—— 与既有数据模型对接的唯一入口。
     *
     * <p>为什么需要它：{@code t_product.price} / {@code t_order.deal_price} 都是
     * {@code DECIMAL(10,2)}，MyBatis-Plus 映射成 {@code BigDecimal}；
     * 而议价内核内部一律用「分」的 {@code long}。所有跨界的金额转换都必须走这个方法，
     * <b>不允许任何地方自己写 {@code multiply(new BigDecimal(100))}</b> ——
     * 那样迟早会有人漏掉 scale 或用了 double 中转。
     *
     * <p>用 HALF_UP 而非截断：DECIMAL(10,2) 最多两位小数，{@code movePointRight(2)}
     * 之后本来就是整数，这里的 {@code setScale} 只是防御性兜底
     * （万一有人传了三位小数的内存值）。
     */
    public static Money ofYuan(BigDecimal yuan) {
        if (yuan == null) {
            throw new IllegalArgumentException("金额不能为 null");
        }
        return new Money(yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /** Money（分）→ BigDecimal（元，2 位小数），用于写回数据库。 */
    public BigDecimal toYuan() {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.fen, other.fen));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(this.fen, other.fen));
    }

    public boolean isLessThan(Money other) {
        return this.fen < other.fen;
    }

    public boolean isAtLeast(Money other) {
        return this.fen >= other.fen;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.fen, other.fen);
    }

    /** 展示用。只在日志与话术渲染时调用，不参与任何计算。 */
    public String display() {
        long abs = Math.abs(fen);
        return (fen < 0 ? "-¥" : "¥") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    @Override
    public String toString() {
        return display();
    }
}
