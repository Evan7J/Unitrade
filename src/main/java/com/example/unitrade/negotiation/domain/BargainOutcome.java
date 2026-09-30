package com.example.unitrade.negotiation.domain;

/**
 * 议价轮次的决策结果 —— 用 sealed interface 建模「封闭的决策空间」。
 *
 * <p>为什么用 sealed 而不是 enum：每种结果携带的数据不同
 * （成交带价格、挂起带原因与出价、僵局只带轮次），enum 会被迫塞进一堆用不上的字段。
 * sealed 让每个分支只带自己需要的字段，同时编译器知道"一共只有这几种"。
 *
 * <p><b>为什么不直接用 switch 模式匹配</b>（{@code case Accepted a -> ...}）：
 * 那是 Java 21 才正式的特性（JEP 441），Java 17 上要用 {@code --enable-preview}，
 * 而带 preview 标记的 class 文件跨 JDK 版本不能运行 —— 生产代码里不能用。
 * 所以改成让每个子类型实现 {@link #branchName()}：
 * 新增子类型时编译期强制实现，穷尽性一样有保障，且不依赖 preview。
 *
 * <p>分支名同时是路由键与评测标签，一件事只定义一次。
 */
public sealed interface BargainOutcome {

    /** 分支名：用于图条件边路由、日志字段、评测集标签。 */
    String branchName();

    /** 买家出价被接受（给出成交建议，不自动下单）。 */
    record Accepted(Money price, int round) implements BargainOutcome {
        @Override
        public String branchName() {
            return "ACCEPTED";
        }
    }

    /** 不接受买家出价，给出还价。 */
    record Countered(Quote quote) implements BargainOutcome {
        @Override
        public String branchName() {
            return "COUNTERED";
        }
    }

    /**
     * 出价越界，挂起并转人工。
     *
     * <p>为什么是"挂起"而不是"拒绝"：买家报低价是完全合法的用户行为，不是程序错误。
     * 详见讲义第 4 讲 —— 这是风控，不是参数校验。
     *
     * <p>注意 {@code offered} 是买家出的价，不是底价。
     * 这个类型里同样没有底价字段。
     */
    record Suspended(SuspensionReason reason, Money offered) implements BargainOutcome {
        @Override
        public String branchName() {
            return "SUSPENDED";
        }
    }

    /** 分精度下已无法继续让价（让价不足 1 分），转人工。 */
    record Stalled(int round, Money lastPrice) implements BargainOutcome {
        @Override
        public String branchName() {
            return "STALLED";
        }
    }

    /** 挂起原因。这些原因会写进风控留痕表，是"拦截 13 次"的口径来源。 */
    enum SuspensionReason {
        /** 买家出价低于卖家底价 —— 典型的越界试探。 */
        OFFER_BELOW_FLOOR("买家出价低于授权下限"),
        /** 引擎算出的报价低于底价 —— 说明策略实现有问题，是告警信号。 */
        QUOTE_BELOW_FLOOR("策略产出报价低于授权下限"),
        /** 报价没有保持单调非增 —— 说明缓存或状态被污染，是告警信号。 */
        QUOTE_NOT_MONOTONIC("报价未保持单调非增"),
        /** 超过最大轮次上限。 */
        ROUND_LIMIT_EXCEEDED("超过最大议价轮次"),

        /**
         * 买家情绪投诉（辱骂、威胁差评/举报）—— 由<b>意图</b>触发的挂起。
         *
         * <p>⚠️ 注意这一类虽然也叫"挂起"，但它的 {@code offered} 不是越界出价，
         * 所以它<b>不写 t_bargain_guard_log</b>，也不计入"拦截 N 次"这个指标。
         * 原因：「拦截」这个口径度量的是<b>价格越界</b>（{@code OFFER_BELOW_FLOOR}），
         * 把情绪投诉混进去会让这个数字失去定义 ——
         * 一个数字只有在口径唯一时才可复现。
         *
         * <p>它的可追溯性由 {@code t_negotiation_round}（intent + branch=ESCALATED）承担。
         */
        INTENT_COMPLAINT("买家情绪投诉，已转人工"),

        /**
         * 人工放行了一个<b>低于授权下限</b>的价格。
         *
         * <p>为什么允许而不是拦下：被挂起到人工的，本来就超出了 Agent 的自主范围，
         * 决定权回到了卖家本人。<b>卖家愿意低于自己设的底价卖，是合法的业务决策</b> ——
         * 如果系统在这里再拦一道，就等于替卖家做了决定，HITL 也就没意义了。
         *
         * <p>但必须<b>留痕</b>：这是唯一一条"突破了授权下限"的成交路径，
         * 事后审计要知道它发生过、是谁放的。（对应讲义第 4 讲：
         * 「拦截率是效果指标，误放率是安全指标」—— 人工放行不属于误放，
         * 因为它是被记录、被授权的一次显式决策，不是系统漏过去的。）
         */
        HUMAN_RELEASED_BELOW_FLOOR("人工放行（低于授权下限）");

        private final String description;

        SuspensionReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
