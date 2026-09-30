package com.example.unitrade.llm;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * LLM 调用量计量 —— 按模型累计 token，用来回答"这次到底花了多少"。
 *
 * <h2>为什么不能只记一个总数</h2>
 * 做了分级路由之后，"总 token 量"会变得没有意义：
 * 轻量模型和强模型的 1000 token 不是一个价钱。
 * 所以必须<b>按模型分开累计</b>，成本对照实验才做得起来 ——
 * 否则你只知道"变少了"，但说不清少在哪、值多少钱。
 *
 * <h2>它和 LlmUsageRecorder 的区别</h2>
 * <ul>
 *   <li>{@code LlmUsageRecorder}：<b>按轮次</b>取用（ThreadLocal），用来把成本落到
 *       {@code t_negotiation_round.token_cost}，是"业务埋点"；</li>
 *   <li>本类：<b>按进程</b>累计（LongAdder），用来做对照实验与报表，是"计量表"。</li>
 * </ul>
 * 两者数据来源相同（都来自 API 返回的 usage），但生命周期完全不同，
 * 混在一起就会出现"取走后统计就清零"这种问题。
 *
 * <p>⚠️ 计数口径：只统计<b>真实发生</b>的调用。缓存命中的调用不会进这里，
 * 因为那些调用根本没发出去 —— 缓存省下的钱体现在"总额没变"上，
 * 而不是体现在"记录了一笔金额为 0 的调用"上。
 */
@Component
public class LlmCostMeter {

    private final Map<String, Counters> byModel = new ConcurrentHashMap<>();

    /**
     * 记一次真实调用。
     *
     * @param model            实际使用的模型名（必须来自 API 响应，不是请求参数 ——
     *                         有些网关会做别名映射，响应的 model 才是真相）
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public void record(String model, Integer promptTokens, Integer completionTokens) {
        Counters c = byModel.computeIfAbsent(model == null ? "unknown" : model, k -> new Counters());
        c.calls.increment();
        c.promptTokens.add(nz(promptTokens));
        c.completionTokens.add(nz(completionTokens));
    }

    public Map<String, Counters> byModel() {
        return new LinkedHashMap<>(byModel);
    }

    public long totalCalls() {
        return byModel.values().stream().mapToLong(c -> c.calls.sum()).sum();
    }

    public long totalTokens() {
        return byModel.values().stream()
                .mapToLong(c -> c.promptTokens.sum() + c.completionTokens.sum())
                .sum();
    }

    /** 实验前清零。跨用例不清零的话，第二个实验的基线里会混进第一个实验的调用。 */
    public void reset() {
        byModel.clear();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 单个模型的累计值。 */
    public static final class Counters {
        private final LongAdder calls = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();

        public long calls() {
            return calls.sum();
        }

        public long promptTokens() {
            return promptTokens.sum();
        }

        public long completionTokens() {
            return completionTokens.sum();
        }

        public long totalTokens() {
            return promptTokens() + completionTokens();
        }
    }
}
