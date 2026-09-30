package com.example.unitrade.negotiation.domain;

/**
 * 一次报价的对外结果 —— 可以进日志、进 MQ 消息体、进接口返回值。
 *
 * <p><b>刻意没有 floorPrice 字段。</b>一个不含底价的类型，无论怎么序列化、
 * 怎么打印都不会泄漏底价。这就是"用类型消除风险"而不是"靠记得别写漏"的具体做法。
 *
 * <p><b>为什么也没有 ratio（本轮让出比例）字段</b>（这是我设计时真实改掉的一处）：
 * 因为 {@code 报价 = 挂牌价 - h × ratio}，只要同时知道 挂牌价、报价、ratio，
 * 就能反解出 h，进而算出底价。ratio 看起来"只是个百分比、不含金额"，
 * 但它和另外两个公开量联立后就是底价的一把钥匙。
 * <b>判据不是"这个字段敏不敏感"，而是"它和别的公开量联立后能不能解出敏感量"。</b>
 *
 * <p>需要 ratio 时不用存 —— 引擎是纯函数，ratio 由 (上架天数, 轮次) 唯一决定，
 * 任何时候都能重算。这是纯函数带来的一个额外好处：对外契约可以做到最小。
 *
 * @param price        本轮报价
 * @param round        轮次序号（从 1 开始）
 * @param strategyName 产出这个报价的策略名。回放时必须能定位到具体策略。
 * @param clamped      是否触发了引擎的安全网裁剪（原始报价低于底价）。
 *                     正常路径恒为 false；为 true 说明策略本身算出了越界值，
 *                     这是告警信号，不是"防御成功"。
 */
public record Quote(
        Money price,
        int round,
        String strategyName,
        boolean clamped) {

    public Quote {
        if (round < 1) {
            throw new IllegalArgumentException("轮次从 1 开始，实际=" + round);
        }
        if (price == null) {
            throw new IllegalArgumentException("报价不能为 null");
        }
    }
}
