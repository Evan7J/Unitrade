package com.example.unitrade.negotiation.talk;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * LLM token 用量记录器。
 *
 * <h2>为什么用 ThreadLocal，而不是"生成器记一个字段、调用方再读"</h2>
 * 图里的 talk 节点可能在<b>任意线程</b>上执行。如果用量存在生成器实例的字段里，
 * 两个会话并发时会出现"A 调用 → B 调用（覆盖）→ A 读到 B 的用量"这种串数据。
 *
 * <p>ThreadLocal 能保证：<b>谁调用、谁读取</b> ——
 * 写和读都发生在同一个节点执行的同一个线程上，天然隔离。
 *
 * <h2>为什么必须"取走并清空"（drain）而不是只读</h2>
 * 一个线程会复用（Tomcat 线程池）。这轮的用量如果没清掉，
 * 会被下一轮一起算进去，成本数字会越滚越大。
 *
 * <h2>为什么要记模型名</h2>
 * 成本口径必须能回答"这个数字是哪个模型跑出来的"。
 * 换模型之后，历史数据要是没有模型名，就没法解释成本为什么变了。
 */
@Component
public class LlmUsageRecorder {

    private final ThreadLocal<Acc> current = ThreadLocal.withInitial(Acc::new);

    /** 记录一次调用的用量与模型名。 */
    public void record(Usage usage, String model) {
        Acc acc = current.get();
        if (model != null && !model.isBlank()) {
            acc.model = model;
        }
        if (usage == null) {
            return;
        }
        acc.promptTokens += nz(usage.getPromptTokens());
        acc.completionTokens += nz(usage.getCompletionTokens());
    }

    /** 取走本线程累计的用量并清空。 */
    public TokenUsage drain() {
        Acc acc = current.get();
        TokenUsage snapshot = new TokenUsage(acc.promptTokens, acc.completionTokens, acc.model);
        current.remove();
        return snapshot;
    }

    /** 一次（或一轮累计）的 token 用量。 */
    public record TokenUsage(int promptTokens, int completionTokens, String model) {
        public int total() {
            return promptTokens + completionTokens;
        }

        public boolean isEmpty() {
            return promptTokens == 0 && completionTokens == 0;
        }
    }

    private static final class Acc {
        int promptTokens;
        int completionTokens;
        String model;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
