package com.example.unitrade.negotiation.talk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 模板话术生成器（默认实现）。
 *
 * <h2>为什么默认用模板而不是模型</h2>
 * 因为<b>"可复现"是硬需求</b>：评测集要跑两次比对结果、回归测试要能断言输出。
 * 话术如果每次都不同，这两件事都做不了。
 * 模板的另一个好处是它<b>结构上不可能说出错误的数字</b> ——
 * 整个模板里只有一个插值点 {@code engineQuote}，模型幻觉在这条路径上不存在。
 *
 * <h2>语气设计（对齐 Agent 人设）</h2>
 * 人设是「个人卖家」而不是「客服」，所以：
 * <ul>
 *   <li>不用"亲""宝贝""感谢您的咨询"这类客服腔 —— 用了买家会期待平台政策，而我们兑现不了；</li>
 *   <li>句子短、口语化，像跟网友聊天；</li>
 *   <li>轮次越往后语气越"到底"，营造真实的谈判感。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "negotiation.talk.generator", havingValue = "template", matchIfMissing = true)
public class TemplateTalkGenerator implements NegotiationTalkGenerator {

    @Override
    public String generate(TalkContext context) {
        String price = format(context.engineQuote());
        return switch (context.branch()) {
            case "ACCEPTED" -> "行，" + price + " 成交，你直接拍就行。";
            case "SUSPENDED" -> "这个价我这边过不了，我叫人工跟你聊一下，稍等。";
            case "ESCALATED" -> "先别急，这单我不跟你绕了，让卖家本人直接跟你聊，他马上回你。";
            case "REFUSED" -> REFUSE_FLOOR;
            case "STALLED" -> "我这边价格已经到底了，" + price + "，能接受就拍吧。";
            case "COUNTERED" -> counterByIntent(context, price);
            default -> "嗯，" + price + " 你看行不行？";
        };
    }

    /**
     * 被问底价时的固定答复 —— <b>一个价格数字都没有</b>。
     *
     * <h2>为什么这条不能用模型生成</h2>
     * 套底价是整套系统里最需要"嘴严"的一类输入。让模型自由发挥，
     * 它可能换个说法把区间描述出来（"我最低也就再让个两百"），
     * 或者干脆把报价当成底价报出去 —— 这类泄漏没有任何一行代码能事后发现。
     * 所以这里和 {@code SUSPEND_REPLY} 用同一套策略：
     * <b>涉及敏感信息的措辞写死在代码里，模型只负责说体面话，不负责守边界。</b>
     */
    private static final String REFUSE_FLOOR =
            "底价这个我真不方便说，说了就没得聊了。你就说个你想出的数，我看看能不能行。";

    /**
     * 先按意图挑说法，认不出来的再退回按轮次。
     *
     * <p>这一步是"意图识别真的接通了"的可见证据：买家施压比价、提附加条件、问商品详情，
     * 会得到三种不同的回应；<b>而价格数字始终是那个引擎报价，一个字都没变</b>。
     * 这就是「意图只影响表达，不影响数值」——如果意图能改价格，
     * 那等于让一段文本绕过了定价策略与授权闸门。
     */
    private String counterByIntent(TalkContext context, String price) {
        String intent = context.intent() == null ? "" : context.intent();
        return switch (intent) {
            case "PRESSURE" -> "别家什么价我不去比，" + price + " 是我这台能给到的数，你先看看成色。";
            case "CONDITION" -> "包邮、赠品这些我确实弄不了，价格上我再让一步：" + price + "，行吗？";
            case "CHITCHAT" -> "东西的情况就这些，价格 " + price + "，你看合不合适？";
            case "ASK_PRICE" -> "我这边能给到 " + price + "，你觉得可以就拍。";
            default -> counterByRound(context, price);
        };
    }

    /**
     * 按轮次调整让价的语气强度。
     *
     * <p>为什么按轮次而不是随机：<b>话术是策略的可观测外观</b>。
     * 前几轮显得有余地、后几轮显得吃紧，买家感受到的"谈判进展"
     * 才和真实的价格曲线一致。如果话术一直很松或一直很硬，
     * 买家会觉得系统在敷衍 —— 这本身就是体验问题，不是小事。
     */
    private String counterByRound(TalkContext context, String price) {
        int round = context.round();
        if (round >= 8) {
            return "真的是这个价了，" + price + "，再低我就不出了。";
        }
        if (round >= 4) {
            return price + " 吧，我这边已经让了不少。";
        }
        return context.buyerMessage() == null || context.buyerMessage().isBlank()
                ? "我看看，" + price + " 给你吧，怎么样？"
                : "这个价我算了下，" + price + " 可以给你。";
    }

    /**
     * 金额展示。
     *
     * <p>用 {@code stripTrailingZeros()} + {@code toPlainString()}：
     * 前者去掉无意义的 ".00"，后者避免输出成科学计数法
     * （{@code new BigDecimal("100.00").stripTrailingZeros()} 会变成 {@code 1E+2}，
     * 直接 toString 会得到 "1E+2" 这种没法看的东西 —— 这是个常见的踩坑点）。
     */
    private String format(BigDecimal price) {
        if (price == null) {
            return "";
        }
        return "¥" + price.stripTrailingZeros().toPlainString();
    }
}
