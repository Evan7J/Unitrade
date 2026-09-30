package com.example.unitrade.negotiation.graph;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 议价意图识别（规则版）。
 *
 * <h2>为什么这是一个独立的「节点」而不是散落的一堆 if</h2>
 * 它是 Graph 的第一个节点，输出（intent）会被后续节点和评测集共同消费。
 * 把它单独抽出来，带来三件事：
 * <ol>
 *   <li>意图标签集合 = 评测集的标签集合，<b>一处定义两处使用</b>，不会出现两套口径；</li>
 *   <li>可以单独跑准确率评测（Macro-F1 就是这么算出来的）；</li>
 *   <li>将来换成模型分类时，只替换本类，Graph 结构和其他节点完全不动 ——
 *       这就是"节点薄"的回报。</li>
 * </ol>
 *
 * <h2>为什么默认用规则而不是模型</h2>
 * 与话术生成同理：<b>可复现</b>。意图识别要参与评测，如果每次结果都可能不同，
 * Macro-F1 就没法稳定复现，"跑两次结果一致"这条验收标准也过不了。
 * 生产环境可以按 {@code negotiation.intent.classifier=llm} 切到模型版。
 *
 * <p>规则版的另一层价值：它明确划出了"确定性的一部分"。
 * 能确定的事就别交给概率模型，否则等于主动引入不确定性。
 */
@Component
public class IntentClassifier {

    /** 出价：包含金额数字 + 交易动词 */
    private static final Pattern MONEY_IN_MESSAGE =
            Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*(元|块|块钱|rmb|¥)?");

    private static final List<String> PROBE_FLOOR_WORDS =
            List.of("底价", "最低多少", "最低价", "最少多少", "你能接受多少", "能接受多少", "底线",
                    "底在哪", "什么价能卖", "最低能");
    private static final List<String> CONFIRM_WORDS =
            List.of("我要了", "拍了", "成交", "行吧就这个", "可以就这个价", "下单了", "买了", "就它了",
                    "这么定", "就这么着", "说定了", "就这么办", "定了啊");
    private static final List<String> PRESSURE_WORDS =
            List.of("别人家", "别家", "淘宝", "拼多多", "闲鱼上", "官方", "新的才", "太贵了", "太贵",
                    "不值", "坑人", "才这个价", "别的卖家", "外面", "都便宜", "更便宜", "便宜多了");
    private static final List<String> CONDITION_WORDS =
            List.of("包邮", "包个邮", "赠送", "送个", "带上", "发票", "保修", "便宜点就买", "再送");
    private static final List<String> COMPLAINT_WORDS =
            List.of("骗子", "垃圾", "举报", "投诉", "差评", "恶心", "傻", "滚",
                    "太坑", "坑了", "态度差", "太差了", "失望");
    /**
     * 询价。
     *
     * <p>⚠️ 这一组在评测集扩到 200 条后被大幅补充过：
     * 原本只有"多少钱 / 便宜点"等少数几个词，
     * 结果"还能再低吗""再少一点行不行"这类常见说法<b>全部落到了 UNKNOWN</b>，
     * ASK_PRICE 的 recall 一度只有 58.8%。
     *
     * <p>注意判定顺序保证了这里的冲突是可控的：
     * "便宜点就买"会先在 CONDITION（第 ③ 步）被截住，
     * "闲鱼上比你便宜多了"会先在 PRESSURE（第 ⑤ 步）被截住，
     * 所以这里可以放心用"便宜"这个更宽的词。
     */
    private static final List<String> ASK_PRICE_WORDS =
            List.of("多少钱", "什么价", "怎么卖", "价格", "便宜", "能少", "少点", "少一点",
                    "优惠", "再低", "能低", "好商量", "让价", "还价", "讲价");

    /**
     * 商品详情类闲聊。
     *
     * <p>⚠️ 这一组是<b>跑评测之后才补上的</b>：第一版规则忘了写 CHITCHAT，
     * 结果 8 条闲聊消息全部落到 UNKNOWN，CHITCHAT 的 F1 = 0%，
     * 直接把 Macro-F1 从 80%+ 拉到 72.6%。
     *
     * <p>这件事说明了两点：
     * <ol>
     *   <li><b>漏一个类别不是"少一点点"</b> —— Macro-F1 给每个类别同等权重，
     *       整个类别归零的代价是断崖式的；</li>
     *   <li>如果只看 Micro-F1（准确率），这次遗漏只让数字从 76% 掉到 74%，
     *       完全看不出问题。<b>这正是必须用 Macro-F1 的原因。</b></li>
     * </ol>
     */
    private static final List<String> CHITCHAT_WORDS =
            List.of("用了多久", "多长时间", "多久了", "划痕", "磕碰", "磕", "电池", "颜色",
                    "自提", "在哪", "校区", "什么时候", "方便看", "看货", "打游戏", "适合",
                    "配件", "几成新", "成色", "实物图", "有没有货");

    /**
     * 识别意图。
     *
     * <p>判定顺序<b>从强到弱</b>：越具体的意图越先判断。
     * 顺序是有讲究的 —— "包邮我就要"同时命中 CONDITION 和 CONFIRM 词表，
     * 如果 CONFIRM 先判，就会误判成成交确认（而买家其实只是提了个条件）。
     */
    public String classify(String message) {
        if (message == null || message.isBlank()) {
            return Intent.UNKNOWN.name();
        }
        String text = message.trim();

        // ① 套底价：最需要警惕的一类，优先级最高
        if (containsAny(text, PROBE_FLOOR_WORDS) && !hasExplicitOffer(text)) {
            return Intent.PROBE_FLOOR.name();
        }
        // ② 投诉/情绪：需要转人工，不能当普通消息
        if (containsAny(text, COMPLAINT_WORDS)) {
            return Intent.COMPLAINT.name();
        }
        // ③ 附加条件（含"就买"这类伪确认，先判条件）
        if (containsAny(text, CONDITION_WORDS)) {
            return Intent.CONDITION.name();
        }
        // ④ 成交确认
        if (containsAny(text, CONFIRM_WORDS)) {
            return Intent.CONFIRM.name();
        }
        // ⑤ 施压比价
        if (containsAny(text, PRESSURE_WORDS)) {
            return Intent.PRESSURE.name();
        }
        // ⑥ 明确出价（带金额）
        if (hasExplicitOffer(text)) {
            return Intent.OFFER.name();
        }
        // ⑦ 询价
        if (containsAny(text, ASK_PRICE_WORDS)) {
            return Intent.ASK_PRICE.name();
        }
        // ⑧ 商品详情类闲聊 —— 放在询价之后：同时提到价格和详情时，按价格处理更稳妥
        if (containsAny(text, CHITCHAT_WORDS)) {
            return Intent.CHITCHAT.name();
        }
        // ⑨ 兜底
        return Intent.UNKNOWN.name();
    }

    /** 是否包含明确的出价（金额 + 交易意向，或裸金额）。 */
    private boolean hasExplicitOffer(String text) {
        if (MONEY_IN_MESSAGE.matcher(text).find()) {
            // 有金额即认为包含出价意图。
            // 宽松一点是有意的：宁可把"300 卖吗"判成 OFFER 走进闸门校验，
            // 也不要漏判成 CHITCHAT 而绕过了价格校验。
            return true;
        }
        return false;
    }

    private boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 议价意图枚举 —— 同时是评测集的标签集合。
     *
     * <p>九个取值一个不多一个不少：多一个会让评测集分类更细但样本更稀（Macro-F1 更难提），
     * 少一个则会把不同处理路径的消息混在一起。
     */
    public enum Intent {
        /** 询价（"最低多少"） */
        ASK_PRICE,
        /** 出价（"700 卖不卖"） */
        OFFER,
        /** 套底价（"你底价多少"）—— 硬拒答 + 转移 */
        PROBE_FLOOR,
        /** 施压比价（"别家更便宜"）—— 报价不动 + 共情 */
        PRESSURE,
        /** 附加条件（"包邮我就要"）—— 转非价格筹码 */
        CONDITION,
        /** 闲聊 / 问商品详情 */
        CHITCHAT,
        /** 情绪投诉（辱骂、威胁差评）—— 转人工 */
        COMPLAINT,
        /** 成交确认（"行，我要了"）—— 出成交建议 */
        CONFIRM,
        /** 归类不了 —— 兜底 */
        UNKNOWN
    }
}
