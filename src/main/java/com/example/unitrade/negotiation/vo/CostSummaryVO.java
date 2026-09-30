package com.example.unitrade.negotiation.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本汇总（要点 5 的 `¥0.12 / 次` 这类数字的唯一来源）。
 *
 * <p><b>每个数字都必须配口径，否则不可信。</b>
 * 面试官会追问的四件事，VO 里全部显式带上：
 * <ol>
 *   <li>"单次"指什么 → {@link #scope}（整个议价会话，不是单轮）；</li>
 *   <li>单价哪来的 → {@link #rate}；</li>
 *   <li>含不含缓存命中 → {@link #includesCacheHit}；</li>
 *   <li>真实调用还是估算 → {@link #source}（真实调用返回的 usage）。</li>
 * </ol>
 */
public record CostSummaryVO(
        /** 样本：多少个议价会话 */
        long sessionCount,
        /** 样本：多少轮 */
        long roundCount,
        /** 平均每会话轮次 */
        double avgRoundsPerSession,
        /** 累计输入 token */
        long promptTokens,
        /** 累计输出 token */
        long completionTokens,
        /** 每会话平均输入 token */
        double avgPromptPerSession,
        /** 每会话平均输出 token */
        double avgCompletionPerSession,
        /** 累计成本（元） */
        BigDecimal totalCost,
        /** ★ 每会话平均成本（元）—— 简历上 `¥0.12` 对应的字段 */
        BigDecimal avgCostPerSession,
        /** 每轮平均成本（元），作为对照口径 */
        BigDecimal avgCostPerRound,
        /** 实际使用的模型 */
        String modelName,
        /** 单价口径 */
        String rate,
        /** "单次"的范围说明 */
        String scope,
        /** 是否包含缓存命中的调用 */
        boolean includesCacheHit,
        /** 数据来源说明 */
        String source) {

    public static CostSummaryVO of(long sessionCount, long roundCount,
                                   long promptTokens, long completionTokens,
                                   BigDecimal totalCost, String modelName, String rate) {
        double sessions = Math.max(1, sessionCount);
        double rounds = Math.max(1, roundCount);
        return new CostSummaryVO(
                sessionCount, roundCount,
                round(roundCount / sessions),
                promptTokens, completionTokens,
                round(promptTokens / sessions),
                round(completionTokens / sessions),
                totalCost,
                totalCost.divide(BigDecimal.valueOf(sessionCount), 6, RoundingMode.HALF_UP),
                totalCost.divide(BigDecimal.valueOf(rounds), 6, RoundingMode.HALF_UP),
                modelName, rate,
                "单次 = 一个完整的议价会话（发起 → 若干轮还价 → 结束），不是单个轮次",
                false,
                "LLM 每次调用返回的真实 usage（prompt/completion tokens），非估算");
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
