package com.example.unitrade.negotiation.talk;

import com.example.unitrade.llm.LlmCostMeter;
import com.example.unitrade.llm.LlmResultCache;
import com.example.unitrade.llm.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * LLM 话术生成器（用强模型，语言更自然）。
 *
 * <p>启用方式：{@code negotiation.talk.generator=llm}。默认为 template，
 * 因为评测与回归测试需要可复现的输出。
 *
 * <h2>三层防护（缺一不可）</h2>
 * <ol>
 *   <li><b>prompt 里只有一个价格</b>：{@link TalkContext} 结构上不含底价，
 *       所以模型<b>根本不知道</b>底价是多少，而不是"被要求别说"；</li>
 *   <li><b>输出数值一致性校验</b>：生成后抽取话术里的价格，
 *       凡与引擎报价不一致的一律判为不合格；</li>
 *   <li><b>模板兜底</b>：校验不过或调用失败时退到
 *       {@link TemplateTalkGenerator}，保证永远有一句能发出去的话。</li>
 * </ol>
 *
 * <p><b>为什么第 3 条不能省</b>：LLM 是外部依赖，会超时、会限流、会返回空。
 * 如果没兜底，买家就会看到"系统异常"——一次议价中断，前面几轮的价格沟通全白费。
 * 降级不是"降格"，是"这个功能不该因为外部依赖抖动而消失"。
 */
@Component
@ConditionalOnProperty(name = "negotiation.talk.generator", havingValue = "llm")
public class LlmTalkGenerator implements NegotiationTalkGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmTalkGenerator.class);

    /**
     * 从话术里抽取"带 ¥ 前缀的金额"。
     *
     * <p>为什么必须带 ¥ 才能被抽到：话术里天然存在非价格数字
     * （"9 成新""用了 2 年""还剩 3 天"），裸数字正则会把它们全捞进来，
     * 造成大量误判。所以 prompt 里硬性要求价格写成 {@code ¥数字}，
     * 校验就只认这个形式。
     */
    // 价格抽取正则已收敛到接口 NegotiationTalkGenerator.priceConsistent(...)。
    // 原因：人工放行后从 Graph 断点续跑（resume）拿回来的话术，实测同样可能是
    // 上一轮的旧内容（Checkpoint 会把旧值带过来），也要过同一道校验。
    // 两份正则迟早会写歪，所以只保留一份定义。

    private final ChatClient chatClient;

    /** token 用量记录器：成本埋点的落点。 */
    private final LlmUsageRecorder usageRecorder;

    /** 模型分级路由：话术生成是唯一走强档的环节（见 ModelRouter 的说明）。 */
    private final ModelRouter modelRouter;

    /** 内容哈希缓存：同一段 prompt 不重复花钱。 */
    private final LlmResultCache cache;

    /** 全局计量表：对照实验要按模型分别统计 token。 */
    private final LlmCostMeter costMeter;

    /** 兜底实现：直接实例化而不是注入，避免两个条件 Bean 相互依赖。 */
    private final TemplateTalkGenerator fallback = new TemplateTalkGenerator();

    public LlmTalkGenerator(ChatClient.Builder chatClientBuilder,
                            LlmUsageRecorder usageRecorder,
                            ModelRouter modelRouter,
                            LlmResultCache cache,
                            LlmCostMeter costMeter) {
        this.chatClient = chatClientBuilder.build();
        this.usageRecorder = usageRecorder;
        this.modelRouter = modelRouter;
        this.cache = cache;
        this.costMeter = costMeter;
    }

    @Override
    public String generate(TalkContext context) {
        String userPrompt = buildUserPrompt(context);
        // 话术是这次议价里"买家唯一能感知到"的东西，所以它走强档。
        // 省成本要省在买家看不见的地方（意图分类、参数抽取），而不是这里。
        String model = modelRouter.modelFor(ModelRouter.TaskType.TALK_GENERATE);
        String fingerprint = LlmResultCache.fingerprint(model, SYSTEM_PROMPT, userPrompt);

        // 缓存命中：这一句曾经生成过、也曾经通过校验。
        // ⚠️ 即便命中也要再跑一次数值校验 —— 校验是纯正则、几乎不花钱，
        //    而"把一句没校验过的话发给买家"是不可逆的。
        Optional<String> cached = cache.get("talk", fingerprint);
        if (cached.isPresent() && checkPriceConsistency(cached.get(), context) != null) {
            return cached.get();
        }

        try {
            ChatResponse response = chatClient.prompt()
                    // 动态指定模型 —— 分级路由的落地方式
                    .options(OpenAiChatOptions.builder().model(model).build())
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .chatResponse();

            // 成本埋点：⚠️ 无论话术有没有通过校验，这次调用的钱都已经花出去了。
            // 只统计"成功"的调用会让成本数字偏小 —— 那是自欺，不是核算。
            if (response != null && response.getMetadata() != null) {
                usageRecorder.record(response.getMetadata().getUsage(),
                        response.getMetadata().getModel());
                costMeter.record(response.getMetadata().getModel(),
                        response.getMetadata().getUsage().getPromptTokens(),
                        response.getMetadata().getUsage().getCompletionTokens());
            }

            String raw = (response == null || response.getResult() == null)
                    ? null : response.getResult().getOutput().getText();

            String checked = checkPriceConsistency(raw, context);
            if (checked != null) {
                cache.put("talk", fingerprint, checked);
                return checked;
            }
            log.warn("话术未通过数值一致性校验，降级到模板。branch={} 期望价={} 原始输出={}",
                    context.branch(), context.engineQuote(), raw);
            return fallback.generate(context);
        } catch (Exception e) {
            log.warn("话术生成调用失败，降级到模板：{}", e.getMessage());
            return fallback.generate(context);
        }
    }

    /**
     * 数值一致性校验。
     *
     * <p>校验方向<b>跟着分支翻转</b>：
     * <ul>
     *   <li>普通分支（该报价）：话术里出现的每一个价格都必须等于引擎报价；</li>
     *   <li>{@code REFUSED}（该守口）：话术里出现的价格必须<b>恰好是 0 个</b>。</li>
     * </ul>
     * 第二条容易被忽略，但它是"拒绝透露底价"这个动作的唯一保证 ——
     * 详见 {@link NegotiationTalkGenerator#mustNotQuotePrice(String)}。
     *
     * @return 通过校验的话术；不合格返回 null（调用方会降级）
     */
    private String checkPriceConsistency(String reply, TalkContext context) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String trimmed = reply.trim();

        if (mustNotQuotePrice(context.branch())) {
            // 反向校验：该守口的分支里出现任何价格都是不合格的。
            // 注意"不合格"不等于"危险"—— 它会被退回模板重出，所以最坏结果
            // 也只是措辞变生硬，绝不会把不该说的数字说出去。
            if (!PRICE_IN_TALK.matcher(trimmed).find()) {
                return trimmed;
            }
            log.warn("拒绝套底价的话术里出现了价格，退回模板。branch={} 原始输出={}",
                    context.branch(), trimmed);
            return null;
        }

        // 复用接口上的统一校验（与人工放行续跑那条路径共用同一份定义）
        if (priceConsistent(trimmed, context.engineQuote())) {
            return trimmed;
        }
        // 不合格有两种可能：① 模型编了别的价格（最危险的一类幻听）
        //                  ② 该报价格的分支却没报价格
        log.warn("话术未通过数值一致性校验：branch={} 期望价={} 原始输出={}",
                context.branch(), context.engineQuote(), trimmed);
        return null;
    }

    private String buildUserPrompt(TalkContext context) {
        return """
                【商品】%s（成色：%s）
                【你这次可以报出的价格】%s
                【本轮情况】%s
                【买家意图】%s
                【买家说】%s
                """.formatted(
                nullToEmpty(context.productTitle()),
                conditionText(context.condition()),
                context.engineQuote() == null ? ""
                        : context.engineQuote().stripTrailingZeros().toPlainString(),
                branchHint(context.branch()),
                intentHint(context.intent()),
                nullToEmpty(context.buyerMessage()));
    }

    /**
     * 本轮该怎么处理 —— 由分支决定"动作"，由意图决定"侧重"。
     *
     * <p>分工是刻意的：<b>分支管能不能报价、报多少；意图管怎么措辞。</b>
     * 两者混在一起，就会出现"意图文本影响了价格"的可能，那是不能接受的。
     */
    private String branchHint(String branch) {
        return switch (branch) {
            case "COUNTERED" -> "买家出价低于你的报价，你需要再报一个价（就是上面那个价格）";
            case "ACCEPTED" -> "买家接受了你之前的报价，确认这笔交易";
            case "SUSPENDED" -> "买家的出价超出了你的授权范围，你没法自己决定，需要转人工处理";
            case "ESCALATED" -> "买家情绪比较激动，这单你不再自己处理，交给卖家本人跟进";
            case "REFUSED" -> """
                    买家在打听你的底价。你【不能】透露任何价格或价格区间，
                    ⚠️ 本次回复里【不允许出现任何 ¥数字】—— 出现即被判不合格。
                    态度要好，把话头转回"让他出个价"上""";
            case "STALLED" -> "价格已经让到底了，维持当前报价，语气可以坚决一点";
            default -> "正常回应买家";
        };
    }

    /**
     * 意图 → 措辞侧重。
     *
     * <p>刻意保留 UNKNOWN 与空值：识别不出来时退回"正常回应"，
     * <b>而不是猜一个</b>。猜错意图只会让话术跑偏，收益远小于风险。
     */
    private String intentHint(String intent) {
        if (intent == null || intent.isBlank()) {
            return "未识别（按普通还价回应）";
        }
        return switch (intent) {
            case "OFFER" -> "买家报了个具体的价";
            case "ASK_PRICE" -> "买家在问价 / 问能不能便宜，别有求必应地往下让";
            case "PRESSURE" -> "买家在拿别家价格施压 —— 共情但别急着降价，守住本轮报价";
            case "CONDITION" -> "买家想要包邮 / 赠品这类附加条件 —— 你没有这个权限，把话题拉回价格";
            case "CHITCHAT" -> "买家在问商品本身（成色、配件、自提）—— 先答问题，价格顺带提一句";
            case "PROBE_FLOOR" -> "买家在套你的底价";
            case "CONFIRM" -> "买家表达了成交意向";
            case "COMPLAINT" -> "买家有情绪 / 在投诉";
            default -> "未识别（按普通还价回应）";
        };
    }

    private String conditionText(Integer condition) {
        if (condition == null) {
            return "未知";
        }
        return switch (condition) {
            case 1 -> "全新";
            case 2 -> "几乎全新";
            case 3 -> "轻微使用痕迹";
            case 4 -> "明显使用痕迹";
            default -> "未知";
        };
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 人设与禁令。
     *
     * <p>注意这里<b>只约束表达，不承担安全边界</b> —— 价格、授权区间、下单权限
     * 全都由代码控制，prompt 里没有任何一句在"防止越权"。
     * 因为 prompt 是概率性的，越狱和幻觉都能绕过它；能放进代码的约束就不要留给提示词。
     */
    private static final String SYSTEM_PROMPT = """
            你是「UniTrade 校园二手交易平台」上的一位普通个人卖家，正在和买家聊一件自用闲置。
            你不是客服，也不是平台官方，说话要像普通学生网友。
            【语气】随和、简短、口语化，每次回复 1-2 句，不要长篇大论。
            【禁止】不用"亲""宝贝""亲亲"这类客服腔；
                    不承诺包邮、保修、发票、7 天无理由（你没有这个权限）；
                    不提"系统""平台规定""底价""我的最低价"这类说法；
                    不要编造任何价格。
            【价格】本次你只能报出用户消息里给出的那一个价格，并且所有价格都要写成 ¥数字 的形式。
                    ⚠️ 不要复述买家的出价数字（比如"560 太低了"也不要写 560），
                    只说你自己能报的那个价 —— 校验会检查话术里的每一个金额，
                    出现第二个价格就会被判不合格、退回模板。
            【格式】不要使用 emoji，不要使用 Markdown 标记（#、*、-、``` 等），用自然文字。
            """;
}
