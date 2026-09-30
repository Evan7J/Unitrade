package com.example.unitrade.negotiation.talk;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 议价话术生成器。
 *
 * <h2>这一层存在的原因：把「钱的决策」和「语言的表达」分开</h2>
 * 报价由 {@code PricingEngine} 用代码算好、经闸门校验之后，才轮到话术。
 * 话术生成器的职责<b>只有一个</b>：把已经定下来的数字说得体面。
 * 它不参与任何计算，也无权改变价格。
 *
 * <h2>为什么有两种实现</h2>
 * <ul>
 *   <li>{@link TemplateTalkGenerator}（默认）—— 纯模板，零成本、零延迟、
 *       <b>输出完全可复现</b>。回归测试和评测集需要它：如果话术每次都不同，
 *       "跑两次结果一致"这条验收标准就永远无法通过；</li>
 *   <li>{@link LlmTalkGenerator} —— 走强模型，语言更自然，
 *       适合真实演示环境。它内置数值一致性校验与模板兜底。</li>
 * </ul>
 */
public interface NegotiationTalkGenerator {

    /**
     * 生成一轮话术。
     *
     * <h2>TalkContext 的设计约束（很重要）</h2>
     * 这个 record 的字段集合 = 它能泄漏的信息集合：
     * <ul>
     *   <li>record 的 toString() 会打印所有字段；</li>
     *   <li>它还要被拼进 prompt —— 一旦某个字段在这儿，就等于进了模型上下文。</li>
     * </ul>
     * 所以这里<b>刻意没有底价、没有可让空间、没有 pMax</b>，
     * 只有 {@link #engineQuote} 这一个价格数字，且它已经是可以对外报出的价。
     *
     * <p>这不是"记得别写底价"，而是<b>让底价在类型上就进不来</b> ——
     * 直接构造这个 record 的地方根本拿不到底价（它在 pricing 包内）。
     *
     * @param productTitle  商品标题
     * @param condition     成色（1全新 2几乎全新 3轻微使用 4明显使用）
     * @param listPrice     挂牌价（买家本就看得到，不是敏感信息）
     * @param engineQuote   引擎算出的本轮报价 —— <b>prompt 里唯一允许出现的数字</b>
     * @param buyerMessage  买家原话
     * @param branch        本轮分支（ACCEPTED/COUNTERED/SUSPENDED/REFUSED/...），决定语气
     * @param round         轮次
     * @param intent        意图识别结果（{@code IntentClassifier.Intent} 的名字）。
     *                      <p><b>它只影响措辞，不影响任何数值</b> ——
     *                      价格永远是定价引擎算完、闸门校验过的那一个。
     *                      加这个字段是为了让"识别出了意图"真的产生行为差异：
     *                      买家在施压比价、还是在问商品详情，该得到不一样的说法。
     */
    String generate(TalkContext context);

    /**
     * 话术入参 —— 字段集合即泄漏面，见接口注释。 */
    record TalkContext(
            String productTitle,
            Integer condition,
            BigDecimal listPrice,
            BigDecimal engineQuote,
            String buyerMessage,
            String branch,
            int round,
            String intent) {
    }

    /**
     * 该分支的话术<b>不允许出现任何价格</b>。
     *
     * <h2>为什么需要一条"反向校验"</h2>
     * 前面的 {@link #priceConsistent} 管的是"报的价格必须等于引擎给的价"，
     * 它默认话术里<b>应该</b>有一个价格。但「拒绝透露底价」这一类分支恰恰相反：
     * 它一句价格都不该有 —— 一旦模型在这儿写出一个数字，
     * 无论那个数字是多少，都是一次<B>没有依据的报价</b>。
     *
     * <p>所以校验方向要跟着分支翻转。同一个正则，两种判定：
     * <ul>
     *   <li>普通分支：出现的每个价格都必须 == 引擎报价；</li>
     *   <li>本分支：出现的价格必须恰好是 0 个。</li>
     * </ul>
     *
     * <p>这也是"禁止项落代码不落 prompt"的一个具体例子：
     * prompt 里当然也写了"不要报价"，但那只是提醒 ——
     * <b>真正的保证是这里的校验</b>，不合格直接退回模板重出。
     */
    default boolean mustNotQuotePrice(String branch) {
        return "REFUSED".equals(branch);
    }

    /**
     * 话术里的"带 ¥ 前缀的金额"。
     *
     * <p>为什么必须带 ¥ 才算价格：话术里天然存在非价格数字
     * （"9 成新""用了 2 年""还剩 3 天"），裸数字正则会把它们全捞进来，
     * 造成大量误判。所以 prompt 里硬性要求价格写成 {@code ¥数字}，
     * 校验就只认这个形式。
     */
    Pattern PRICE_IN_TALK =
            Pattern.compile("¥\\s*(\\d+(?:\\.\\d{1,2})?)");

    /**
     * 校验话术里报出的价格是否与期望值一致。
     *
     * <h2>为什么这个校验要放在接口上（而不是只在 LlmTalkGenerator 里）</h2>
     * 因为<b>任何一条话术来源都必须过这一关</b>，不只是 LLM：
     * 人工放行后从 Graph 断点续跑（{@code resume}）拿回来的话术，
     * 实测同样可能是上一轮的旧内容（Checkpoint 会把旧值带过来）。
     * 分散写两份正则迟早会写歪，所以收敛到这里 ——
     * 一处定义，所有来源共用。
     *
     * @return true = 话术里出现的价格全部等于 expected；false = 不一致或压根没报价格
     */
    default boolean priceConsistent(String reply, BigDecimal expected) {
        if (reply == null || reply.isBlank() || expected == null) {
            return false;
        }
        Matcher matcher = PRICE_IN_TALK.matcher(reply.trim());
        boolean sawPrice = false;
        while (matcher.find()) {
            sawPrice = true;
            if (new BigDecimal(matcher.group(1)).compareTo(expected) != 0) {
                return false;
            }
        }
        return sawPrice;
    }
}
