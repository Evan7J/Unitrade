package com.example.unitrade.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型分级路由 —— 按「任务复杂度」选择模型档位，而不是所有环节都上最贵的模型。
 *
 * <h2>为什么值得做</h2>
 * 一次议价里 LLM 出现在好几个位置，但它们的难度完全不同：
 * <ul>
 *   <li><b>意图分类</b>：把「能不能便宜点」归到 9 个标签之一 —— 这是分类任务，
 *       输入几十个字、输出一个词，用强模型属于浪费；</li>
 *   <li><b>参数抽取</b>：把「有没有两千以内的 iPad」拆成结构化条件 —— 同上，规整的抽取任务；</li>
 *   <li><b>话术生成</b>：要口语自然、要符合人设、还不能乱报价 —— <b>这个真的需要强模型</b>。</li>
 * </ul>
 * 前两类是"模式识别"，第三类是"生成"。把它们分开，成本就能显著压下来，
 * 而用户能感知到的质量（话术自然度）一点不掉。
 *
 * <h2>两档怎么定的（不是拍脑袋）</h2>
 * 依据是阿里云百炼官方文档的能力分档表：
 * <ul>
 *   <li>「轻量低成本」档：{@code qwen3.8-flash}、<b>{@code deepseek-v4-flash}</b>、MiniMax-M2.5</li>
 *   <li>「平衡」档：{@code qwen3.7-plus}、<b>{@code deepseek-v4-pro}</b>、glm-5.2</li>
 * </ul>
 * 两档都选 DeepSeek 系，是为了让"成本差异"尽量只来自档位本身，
 * 而不是混入不同厂商的定价策略 —— <b>对照实验要控制变量</b>。
 *
 * <h2>⭐ {@code routing-enabled=false} 就是对照组</h2>
 * 关掉路由时<b>所有任务都走强模型</b>，这正是"全量强模型"的基线。
 * 所以成本对照实验不需要两套代码，跑同一个链路、改一个配置就行 ——
 * 这样报告出来的降幅才是同一个程序在不同配置下的差异，
 * 而不是"两份代码之间的差异"（后者没法证明是路由带来的）。
 */
@Component
public class ModelRouter {

    /** 任务类型 —— 决定档位的唯一输入。 */
    public enum TaskType {
        /** 意图分类：短文本 → 固定标签集合。 */
        INTENT_CLASSIFY,
        /** 参数抽取：口语 → 结构化筛选条件。 */
        PARAM_EXTRACT,
        /** 话术生成：要自然、要贴人设、要守住价格。 */
        TALK_GENERATE
    }

    private final String lightModel;
    private final String strongModel;
    private final boolean routingEnabled;

    public ModelRouter(
            @Value("${llm.light-model:deepseek-v4-flash}") String lightModel,
            @Value("${llm.strong-model:deepseek-v4-pro}") String strongModel,
            @Value("${llm.routing-enabled:true}") boolean routingEnabled) {
        this.lightModel = lightModel;
        this.strongModel = strongModel;
        this.routingEnabled = routingEnabled;
    }

    /**
     * 本任务该用哪个模型。
     *
     * <p>注意路由只决定"用哪个模型"，<b>不决定要不要调模型</b>。
     * 有些环节根本不该调模型（比如报价计算），那种情况直接不该走到这里 ——
     * 用路由去"省掉"一次调用是把两个概念混在一起了。
     */
    public String modelFor(TaskType task) {
        if (!routingEnabled) {
            // 对照组：一律强模型
            return strongModel;
        }
        return switch (task) {
            case INTENT_CLASSIFY, PARAM_EXTRACT -> lightModel;
            case TALK_GENERATE -> strongModel;
        };
    }

    public String lightModel() {
        return lightModel;
    }

    public String strongModel() {
        return strongModel;
    }

    public boolean routingEnabled() {
        return routingEnabled;
    }

    /** 该任务是否被降级到轻量档 —— 用于在日志/统计里标出"这次省钱了"。 */
    public boolean isDowngraded(TaskType task) {
        return !strongModel.equals(modelFor(task));
    }
}
