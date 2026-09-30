package com.example.unitrade.negotiation.pricing;

import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.domain.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定价引擎 —— 全系统<b>唯一</b>做「比例 → 价格」和「取整方向」的地方。
 *
 * <h2>它是纯函数</h2>
 * <ul>
 *   <li>无状态：字段构造后不可变，没有计数器、没有缓存；</li>
 *   <li>无 IO：不读数据库、不读系统时钟、不调模型；</li>
 *   <li>输入不可变：{@link AuthorizedRange} 是 record。</li>
 * </ul>
 * 推出两个后果：
 * <ol>
 *   <li><b>天然线程安全</b> —— "要不要给定价加锁"这个问题根本不存在；</li>
 *   <li><b>同一输入必然同一输出</b> —— 这是评测与回放可复现的前提。</li>
 * </ol>
 *
 * <h2>从 demo 到工程的一处真实改动</h2>
 * 演示版引擎里有一个 {@code int clampCount} 可变字段，用来统计越界裁剪次数。
 * 搬进工程时删掉了，改成通过 {@link Quote#clamped()} 把信息随返回值传出去。理由：
 * <ul>
 *   <li>可变字段破坏线程安全（纯函数的第一个好处当场没了）；</li>
 *   <li>累积计数无法区分"是哪一次调用越界了"，排障时等于没有信息；</li>
 *   <li>统计本来就该由调用方（监控/指标）做，不该由计算组件兼任。</li>
 * </ul>
 * <b>一个组件同时负责"算"和"统计"，它的可测试性就开始下降 —— 这是很常见的退化路径。</b>
 */
public final class PricingEngine {

    /** 不可变策略注册表：key 是业务名（不是 bean 名），value 是策略实现。 */
    private final Map<String, PricingStrategy> strategies;

    /** 当前生效的策略名（来自配置）。 */
    private final String activeStrategyName;

    /**
     * @param all                全部可用策略。用 {@code List} 注入而不是 {@code Map}，
     *                           是为了让 key 由策略自己声明（{@code name()}），
     *                           而不是依赖 Spring 的 bean 名 —— 后者和配置里写的业务名
     *                           很容易不一致，那是个只在运行时才炸的坑。
     * @param activeStrategyName 生效策略名
     * @throws IllegalStateException 策略名重复，或生效策略不存在
     */
    public PricingEngine(List<PricingStrategy> all, String activeStrategyName) {
        if (all == null || all.isEmpty()) {
            throw new IllegalStateException("策略列表不能为空");
        }
        Map<String, PricingStrategy> map = new LinkedHashMap<>();
        for (PricingStrategy strategy : all) {
            if (map.put(strategy.name(), strategy) != null) {
                // 重名在构造期就炸 —— 这是"启动即失败"，而不是运行到某条分支才发现
                throw new IllegalStateException("策略名重复: " + strategy.name());
            }
        }
        if (!map.containsKey(activeStrategyName)) {
            throw new IllegalStateException(
                    "生效策略不存在: " + activeStrategyName + "，可用: " + map.keySet());
        }
        this.strategies = Map.copyOf(map);   // 不可变：运行期无法被篡改
        this.activeStrategyName = activeStrategyName;
    }

    /** 用配置指定的策略报价。 */
    public Quote quote(AuthorizedRange range, int round) {
        return quoteWith(activeStrategyName, range, round);
    }

    /**
     * 用指定策略报价 —— 评测对比用。
     *
     * <p>这个方法存在的意义就是策略模式的存在意义：
     * <b>同一组输入能横向跑遍所有策略，一次产出对比报告</b>。
     * if-else 写法做不到这件事（你没法"遍历 if 分支"）。
     */
    public Quote quoteWith(String strategyName, AuthorizedRange range, int round) {
        PricingStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalStateException("策略不存在: " + strategyName);
        }
        BigDecimal ratio = strategy.ratioAt(range, round);
        return toQuote(range, round, ratio, strategyName);
    }

    /**
     * 比例 → 价格映射。整个系统只有这一处做这件事。
     *
     * <p><b>取整方向是业务决策，不是技术细节；而取整「位置」决定偏差方向。</b>
     * 这里有一个我写测试时才发现的坑，两句话只差一个字，方向完全相反：
     * <ul>
     *   <li>对<b>让价额</b> DOWN → 让价更少 → 报价更高 → <b>偏卖家</b>（本实现采用）；</li>
     *   <li>对<b>报价</b>　 DOWN → 报价更低 → 让价更多 → <b>偏买家</b>。</li>
     * </ul>
     * 两者只差 1 分，都会"看起来正常"，但商业含义相反。
     * 这个 Agent 是代表卖家利益的，所以选偏卖家的一侧 ——
     * 而且它还有第二个好处：让价被向下取整后只会更少，
     * 于是"不击穿底价"的保证更强（让价 ≤ h·p < h·pMax）。
     *
     * <p>一句话记住：<b>"向下取整"这句话本身没有意义，必须说清是对哪个量取的整。</b>
     */
    private static Quote toQuote(AuthorizedRange range, int round, BigDecimal ratio, String strategyName) {
        if (ratio.signum() < 0) {
            // ratio < 0 意味着报价高于挂牌价，是策略实现错误，不是运行时数据问题
            throw new IllegalStateException(
                    "策略 " + strategyName + " 返回了负比例 " + ratio + "（会导致报价高于挂牌价）");
        }

        long headroomFen = range.headroom().fen();
        long listFen = range.listPrice().fen();
        long floorFen = range.floorPrice().fen();

        long deductedFen = BigDecimal.valueOf(headroomFen)
                .multiply(ratio)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();

        long rawFen = listFen - deductedFen;
        boolean clamped = rawFen < floorFen;
        long finalFen = Math.max(rawFen, floorFen);

        return new Quote(new Money(finalFen), round, strategyName, clamped);
    }

    /** 全部可用策略名（不可变视图）。评测遍历用。 */
    public Set<String> availableStrategies() {
        return strategies.keySet();
    }

    /** 当前生效策略名。 */
    public String activeStrategyName() {
        return activeStrategyName;
    }
}
