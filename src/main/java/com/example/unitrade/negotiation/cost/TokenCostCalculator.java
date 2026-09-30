package com.example.unitrade.negotiation.cost;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * token 成本计算器。
 *
 * <h2>⚠️ 单价必须换成你供应商的真实价格</h2>
 * 下面两个默认值只是<b>占位</b>。成本数字能不能站住脚，
 * 完全取决于这两个数 —— 面试官一定会追问"单价从哪来、什么时候取的"。
 * 正确做法：去供应商的定价页抄下来，写进 {@code application.yml}。
 *
 * <h2>为什么按"每百万 token"计价</h2>
 * 这是行业通行的报价单位（$/MTok）。直接用它，
 * 换算时少一次"元/token"这种极易写错数量级的小数。
 *
 * <h2>口径三要素（少了任何一条，数字都不可信）</h2>
 * <ol>
 *   <li><b>输入/输出分开算</b>：输出通常比输入贵好几倍，混在一起会严重低估；</li>
 *   <li><b>含不含缓存命中</b>：命中的调用成本≈0，混进来会拉低平均值；</li>
 *   <li><b>"单次"指什么</b>：本系统按<b>一整个议价会话</b>统计，不是单轮。</li>
 * </ol>
 *
 * <h2>⭐ 为什么要按模型分别计价</h2>
 * 做了模型分级路由之后，"一个单价"就不成立了：
 * 同一轮议价里，意图分类走轻量档、话术生成走强档，
 * 用同一个单价去乘总 token 数，算出来的成本一定不对 ——
 * 而且会<b>系统性低估</b>（强模型那部分的钱被按轻量模型的价格算了）。
 * 所以单价按模型查表，配置在 {@code llm.pricing.<模型名>.*}；
 * 查不到就退回 {@code negotiation.cost.*} 这个全局默认值。
 */
@Component
public class TokenCostCalculator {

    /** 全局默认输入单价（元 / 每百万 token），模型级配置缺失时使用。 */
    private final BigDecimal defaultInputPerMTok;
    /** 全局默认输出单价（元 / 每百万 token）。 */
    private final BigDecimal defaultOutputPerMTok;

    private final Environment environment;

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    public TokenCostCalculator(
            Environment environment,
            @Value("${negotiation.cost.input-per-mtok:1.0}") BigDecimal defaultInputPerMTok,
            @Value("${negotiation.cost.output-per-mtok:8.0}") BigDecimal defaultOutputPerMTok) {
        this.environment = environment;
        this.defaultInputPerMTok = defaultInputPerMTok;
        this.defaultOutputPerMTok = defaultOutputPerMTok;
    }

    /**
     * 计算一次调用的成本（元）。
     *
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param model            实际使用的模型名；为 null 时按全局默认单价算
     */
    public BigDecimal cost(int promptTokens, int completionTokens, String model) {
        BigDecimal inputRate = rateOf(model, "input-per-mtok", defaultInputPerMTok);
        BigDecimal outputRate = rateOf(model, "output-per-mtok", defaultOutputPerMTok);

        BigDecimal input = BigDecimal.valueOf(promptTokens)
                .multiply(inputRate).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(completionTokens)
                .multiply(outputRate).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    /** 兼容旧签名（不带模型名）—— 按全局默认单价计算。 */
    public BigDecimal cost(int promptTokens, int completionTokens) {
        return cost(promptTokens, completionTokens, null);
    }

    /**
     * 查某个模型的单价。
     *
     * <p>用 {@link Environment} 动态查而不是把整张价格表绑成配置类：
     * 模型清单会变（加一个档位就多一组单价），
     * 硬编码成字段意味着每加一个模型都要改 Java 类，而它本来就只是配置。
     */
    private BigDecimal rateOf(String model, String field, BigDecimal fallback) {
        if (model == null || model.isBlank()) {
            return fallback;
        }
        return environment.getProperty("llm.pricing." + model + "." + field, BigDecimal.class, fallback);
    }

    /** 某个模型的单价描述（用于报表里标注口径，避免事后说不清）。 */
    public String rateDescription(String model) {
        return "%s：输入 ¥%s/百万token，输出 ¥%s/百万token".formatted(
                model == null ? "(默认)" : model,
                rateOf(model, "input-per-mtok", defaultInputPerMTok).stripTrailingZeros().toPlainString(),
                rateOf(model, "output-per-mtok", defaultOutputPerMTok).stripTrailingZeros().toPlainString());
    }
}
