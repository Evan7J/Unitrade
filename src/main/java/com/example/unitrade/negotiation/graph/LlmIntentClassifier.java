package com.example.unitrade.negotiation.graph;

import com.example.unitrade.llm.LlmCostMeter;
import com.example.unitrade.llm.LlmResultCache;
import com.example.unitrade.llm.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于模型的意图分类器 —— 走<b>轻量档</b>。
 *
 * <h2>它和 {@link IntentClassifier}（规则版）的分工</h2>
 * <table border="1">
 *   <tr><th></th><th>规则版</th><th>本类（模型版）</th></tr>
 *   <tr><td>输出</td><td>完全可复现</td><td>可能每次略有不同</td></tr>
 *   <tr><td>长尾表达</td><td>词表没覆盖就落到 UNKNOWN</td><td>能兜住没见过的说法</td></tr>
 *   <tr><td>成本</td><td>0</td><td>一次轻量档调用</td></tr>
 *   <tr><td>用途</td><td><b>评测与回归</b>（Macro-F1 要在可复现的前提下才有意义）</td>
 *       <td>生产（真实用户不会按你的词表说话）</td></tr>
 * </table>
 *
 * <p>由 {@code negotiation.intent.classifier} 选择用哪个（默认 {@code rule}）。
 * 分开成两个类而不是在一个类里 if-else，是因为两者的<b>可信度性质不同</b>：
 * 一个是确定性函数，一个是概率输出。混在一起之后，
 * "这个 91.2% 是哪个版本测出来的"就说不清了。
 *
 * <h2>三个设计点</h2>
 * <ol>
 *   <li><b>走轻量档</b>：把「能不能便宜点」归到 9 个标签之一，是典型的模式识别，
 *       不需要强模型（见 {@link ModelRouter}）；</li>
 *   <li><b>结果进内容哈希缓存</b>：「多少钱」「能便宜点吗」这类短句会被反复问到，
 *       这是整套系统里缓存命中率最高的地方 —— 也是本文档"降本"最实在的来源；</li>
 *   <li><b>模型失败时降级到规则版</b>，而不是返回 UNKNOWN：后者会让一次分类失败
 *       直接表现为"系统听不懂人话"，而规则版至少能兜住大部分常见说法。
 *       <b>降级要降到一个还work的东西上，而不是降到一个空值上。</b></li>
 * </ol>
 */
@Component
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    /** 9 个标签只有这一处定义 —— 与评测集的标签集合保持一致。 */
    private static final String SYSTEM_PROMPT = """
            你在解析校园二手交易平台上的议价对话，判断买家这句话属于哪一类意图。
            只能输出下面 9 个标签中的一个，不要输出任何解释或其他文字：
            ASK_PRICE   询价：问价格、问能不能便宜，但没有给出具体金额
            OFFER       出价：给出了一个具体金额
            PROBE_FLOOR 套底价：打听卖家的最低价、底价、底线
            PRESSURE    施压比价：拿别家的价格、新货价格来压价
            CONDITION   附加条件：要包邮、赠品、配件才肯买
            CHITCHAT    商品详情：问成色、配件、自提、使用情况
            COMPLAINT   情绪投诉：辱骂、威胁给差评或举报
            CONFIRM     成交确认：明确表示要买、拍下、成交
            UNKNOWN     以上都不是
            """;

    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final LlmResultCache cache;
    private final LlmCostMeter costMeter;

    /** 降级目标：模型不可用时退回规则版。 */
    private final IntentClassifier ruleFallback;

    public LlmIntentClassifier(ChatClient.Builder chatClientBuilder,
                               ModelRouter modelRouter,
                               LlmResultCache cache,
                               LlmCostMeter costMeter,
                               IntentClassifier ruleFallback) {
        this.chatClient = chatClientBuilder.build();
        this.modelRouter = modelRouter;
        this.cache = cache;
        this.costMeter = costMeter;
        this.ruleFallback = ruleFallback;
    }

    /**
     * 识别意图。
     *
     * @return {@link IntentClassifier.Intent} 的名字；任何异常路径都会退回规则版结果，
     *         永远不会抛出去 —— 意图识别只是编排的一步，不该成为整条链路的单点。
     */
    public String classify(String buyerMessage) {
        if (buyerMessage == null || buyerMessage.isBlank()) {
            return IntentClassifier.Intent.UNKNOWN.name();
        }

        String model = modelRouter.modelFor(ModelRouter.TaskType.INTENT_CLASSIFY);
        String fingerprint = LlmResultCache.fingerprint(model, SYSTEM_PROMPT, buyerMessage);

        Optional<String> cached = cache.get("intent", fingerprint);
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            ChatResponse response = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(model).build())
                    .system(SYSTEM_PROMPT)
                    .user(buyerMessage)
                    .call()
                    .chatResponse();

            if (response != null && response.getMetadata() != null) {
                costMeter.record(response.getMetadata().getModel(),
                        response.getMetadata().getUsage().getPromptTokens(),
                        response.getMetadata().getUsage().getCompletionTokens());
            }

            String raw = (response == null || response.getResult() == null)
                    ? null : response.getResult().getOutput().getText();
            String label = parseLabel(raw);

            if (label != null) {
                cache.put("intent", fingerprint, label);
                return label;
            }
            log.warn("意图分类输出无法识别，降级到规则版：raw={}", raw);
        } catch (Exception e) {
            log.warn("意图分类调用失败，降级到规则版：{}", e.getMessage());
        }

        return ruleFallback.classify(buyerMessage);
    }

    /**
     * 从模型输出里提取标签。
     *
     * <p>用"包含"而不是"相等"：模型经常礼貌地回一句
     * 「这句话属于 OFFER」—— 严格相等会把这种正确答案判成失败。
     * 校验仍然严格（必须是 9 个标签之一），所以不会放过乱答。
     */
    private String parseLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toUpperCase();
        for (IntentClassifier.Intent intent : IntentClassifier.Intent.values()) {
            if (text.contains(intent.name())) {
                return intent.name();
            }
        }
        return null;
    }
}
