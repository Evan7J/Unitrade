package com.example.unitrade.negotiation.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.BargainGuardLog;
import com.example.unitrade.entity.NegotiationRound;
import com.example.unitrade.entity.NegotiationSession;
import com.example.unitrade.entity.Product;
import com.example.unitrade.entity.ProductAuthorization;
import com.example.unitrade.mapper.BargainGuardLogMapper;
import com.example.unitrade.mapper.NegotiationRoundMapper;
import com.example.unitrade.mapper.NegotiationSessionMapper;
import com.example.unitrade.mapper.ProductAuthorizationMapper;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.negotiation.adapter.NegotiationPricingAdapter;
import com.example.unitrade.negotiation.cost.TokenCostCalculator;
import com.example.unitrade.negotiation.domain.BargainOutcome;
import com.example.unitrade.negotiation.domain.Money;
import com.example.unitrade.negotiation.graph.IntentClassifier;
import com.example.unitrade.negotiation.graph.NegotiationGraphConfig;
import com.example.unitrade.negotiation.pricing.AuthorizedRange;
import com.example.unitrade.negotiation.pricing.BuiltInStrategies;
import com.example.unitrade.negotiation.pricing.StallDetector;
import com.example.unitrade.negotiation.talk.LlmUsageRecorder;
import com.example.unitrade.negotiation.talk.NegotiationTalkGenerator;
import com.example.unitrade.negotiation.vo.CostSummaryVO;
import com.example.unitrade.negotiation.vo.NegotiationResultVO;
import com.example.unitrade.negotiation.vo.NegotiationSessionVO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 议价服务 —— 会话生命周期、四重防重、闸门风控的落地处。
 *
 * <h2>职责划分（这一层与 Graph 的边界）</h2>
 * <pre>
 *   NegotiationService（本类）  → 取数、幂等、锁、事务、持久化、留痕
 *   NegotiationGraphConfig      → 纯计算编排：意图 → 定价 → 闸门 → 话术
 * </pre>
 * 图里的节点<b>不碰数据库</b>，只做内存计算。这样图可以被单独测试，
 * 也可以在换掉持久化方案时完全不动 —— 这就是"节点必须薄"的实际收益。
 *
 * <h2>四重防重在这里怎么分布</h2>
 * <ol>
 *   <li><b>messageId 幂等</b>：{@code redis SET NX EX}（快路径）；失败时释放 key 允许重试；</li>
 *   <li><b>轮次序号单调校验</b>：更新会话时 {@code WHERE round_no = 读到的值} 做 CAS ——
 *       并发下只有一个能成功，其余收到 409；</li>
 *   <li><b>会话级串行化</b>：Graph 以 {@code threadId = sessionNo} 执行，
 *       同一会话的 Checkpoint 序列天然串行；</li>
 *   <li><b>数据库唯一索引</b>：{@code uk_session_round} + {@code uk_message}，落库兜底。</li>
 * </ol>
 *
 * <p><b>这四重的职责并不对等</b>：①② 是快路径（省一次 LLM 调用），③④ 才是正确性保证。
 * 所以 Redis 不可用时会<b>放行</b>而不是报错 —— 见 {@link #offer} 里的说明。
 * 反过来，如果让 Redis 变成"挂了就不能议价"的单点，那就是把优化当成了依赖。
 *
 * <h2>为什么用乐观锁（CAS）而不是 Redis 分布式锁</h2>
 * 这里的并发冲突不是"重复计算多花了钱"，而是"同一轮被写两次"。
 * CAS 天然表达"这次写入必须基于我读到的那个版本"，
 * 而且不引入锁过期、锁误删、Redisson 这些额外复杂度。
 * <b>能用一条 SQL 表达的正确性，不要用分布式锁去模拟。</b>
 */
@Service
@RequiredArgsConstructor
public class NegotiationService {

    private static final Logger log = LoggerFactory.getLogger(NegotiationService.class);

    /** 幂等键前缀；TTL 30 分钟覆盖"网络重试"与"用户手动重发"的常见窗口。 */
    private static final String IDEM_PREFIX = "negotiation:idem:";

    /**
     * 挂起转人工时的固定文案。
     *
     * <p>刻意用常量而不是话术生成器：这是<b>系统通知</b>，不是卖家说的话。
     * 而且它必须每轮都稳定出现 —— 不能受 Checkpoint 恢复影响（原因见 doOffer 里的说明）。
     */
    private static final String SUSPEND_REPLY = "这个价我这边过不了，我叫人工跟你聊一下，稍等。";

    private static final String PHASE_BARGAINING = "BARGAINING";
    private static final String PHASE_AGREED = "AGREED";
    private static final String PHASE_SUSPENDED = "SUSPENDED";
    private static final String PHASE_STALLED = "STALLED";
    /** 人工拒绝后回到这个终态（不再接受自动报价，也不再进人工队列） */
    private static final String PHASE_REJECTED = "REJECTED";

    /**
     * 卖家拒绝时的固定文案。同 {@link #SUSPEND_REPLY} 的理由：
     * 这是系统通知，不该由话术生成器产出，否则每轮内容不稳定。
     */
    private static final String REJECT_REPLY = "卖家这边觉得这个价不太合适，你再考虑一下？";

    /**
     * 买家情绪投诉、挂起转人工时的固定文案。
     *
     * <p>理由同 {@link #SUSPEND_REPLY}，而且这里还多一层：
     * ESCALATED 走的是 human_takeover 那条边，被 {@code interruptBefore} 挡住后
     * <b>talk 节点根本不会执行</b>，state 里的 reply 是上一轮的旧话术。
     * 从图里取 reply 会直接把上一轮的价格说给一个正在气头上的买家。
     */
    private static final String ESCALATE_REPLY = "先别急，这单我交给卖家本人直接跟你聊，他马上回你。";

    /**
     * Agent 的自主轮次上限（对应简历要点 1「最大 N 轮上限」）。
     *
     * <p>为什么必须有这个上限：议价是<b>零和博弈</b>，买家有动机一直磨。
     * 而我们用的是"首轮锚定 + 逐轮衰减"，每多一轮就让出更多 ——
     * 没有上限的话，足够耐心的买家可以把报价一路磨到授权边界附近。
     * <b>自主范围必须有边界，超过了就交回人。</b>
     */
    @org.springframework.beans.factory.annotation.Value("${negotiation.max-rounds:5}")
    private int maxRounds = 5;

    private final ProductMapper productMapper;
    private final ProductAuthorizationMapper authorizationMapper;
    private final NegotiationSessionMapper sessionMapper;
    private final NegotiationRoundMapper roundMapper;
    private final BargainGuardLogMapper guardLogMapper;
    private final NegotiationPricingAdapter adapter;
    private final StringRedisTemplate stringRedisTemplate;

    /** 议价状态图（在 NegotiationGraphConfig 里构建并做过结构校验）。 */
    private final CompiledGraph negotiationGraph;

    /** 话术生成器：仅在 Graph resume 失败时兜底直出，保证人工放行不会卡住。 */
    private final NegotiationTalkGenerator talkGenerator;

    /** token 用量记录器：人工放行这条路径自己取走用量。 */
    private final LlmUsageRecorder usageRecorder;

    /** token 成本计算器（单价来自配置，见 TokenCostCalculator 的说明）。 */
    private final TokenCostCalculator tokenCostCalculator;

    /**
     * 意图识别器。
     *
     * <p>图里已经有一个意图节点了，这里为什么还要一个？
     * 因为"轮次用尽"是在进图<b>之前</b>就提前返回的路径（不再计算新报价），
     * 拿不到图里算出来的 intent。为了不在 round 表里写一条假的 "OFFER"，
     * 这里就地再识别一次 —— 它是纯函数，代价是几微秒。
     */
    private final IntentClassifier intentClassifier;

    // ==================================================================
    // 卖家：配置议价底价
    // ==================================================================

    @Transactional
    public void configureAuthorization(Long productId, BigDecimal floorPrice) {
        Long sellerId = JwtInterceptor.getCurrentUserId();
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (!product.getUserId().equals(sellerId)) {
            throw new BusinessException(403, "只能为自己的商品设置底价");
        }
        if (floorPrice == null || floorPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("底价必须大于 0");
        }
        if (floorPrice.compareTo(product.getPrice()) > 0) {
            throw new BusinessException("底价不能高于挂牌价");
        }

        ProductAuthorization existing = authorizationMapper.selectOne(
                new LambdaQueryWrapper<ProductAuthorization>()
                        .eq(ProductAuthorization::getProductId, productId));
        if (existing == null) {
            ProductAuthorization auth = new ProductAuthorization();
            auth.setProductId(productId);
            auth.setSellerId(sellerId);
            auth.setFloorPrice(floorPrice);
            auth.setStrategyName(BuiltInStrategies.PRODUCTION_STRATEGY);
            auth.setVersion(1);
            authorizationMapper.insert(auth);
            log.info("议价授权已创建：productId={} version=1", productId);
        } else {
            existing.setFloorPrice(floorPrice);
            existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            authorizationMapper.updateById(existing);
            // 版本 +1 只影响新会话：进行中的会话用创建时的快照，保证可追溯
            log.info("议价授权已更新：productId={} version={}", productId, existing.getVersion());
        }
    }

    // ==================================================================
    // 买家：发起议价
    // ==================================================================

    @Transactional
    public NegotiationResultVO start(Long productId) {
        Long buyerId = JwtInterceptor.getCurrentUserId();
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException("该商品当前不可议价");
        }
        if (product.getUserId().equals(buyerId)) {
            throw new BusinessException("不能和自己的商品议价");
        }

        ProductAuthorization auth = authorizationMapper.selectOne(
                new LambdaQueryWrapper<ProductAuthorization>()
                        .eq(ProductAuthorization::getProductId, productId));
        if (auth == null) {
            throw new BusinessException("卖家还没有开放议价（未设置底价）");
        }

        NegotiationSession existing = findSession(productId, buyerId);
        if (existing != null) {
            return currentState(existing);
        }

        NegotiationSession session = new NegotiationSession();
        session.setSessionNo(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        session.setProductId(productId);
        session.setBuyerId(buyerId);
        session.setSellerId(product.getUserId());
        session.setAuthVersion(auth.getVersion());
        // ↓ 授权区间快照：本场谈判全程以此为准
        session.setSnapshotListPrice(product.getPrice());
        session.setSnapshotFloorPrice(auth.getFloorPrice());
        session.setSnapshotDaysListed(daysListed(product.getCreateTime()));
        session.setPhase(PHASE_BARGAINING);
        session.setRoundNo(1);
        sessionMapper.insert(session);

        // 首轮：走同一个 Graph，保证"首轮报价"和"后续轮次报价"用的是同一套规则。
        // 如果首轮单独写一段代码算，两者迟早会不一致 —— 这是最典型的"双份真相"。
        GraphOutcome outcome = executeGraph(session, null, "", 1, auth.getStrategyName(), product);
        session.setCurrentQuote(outcome.quote());
        sessionMapper.updateById(session);

        saveRound(session, 1, "ASK_PRICE", null, outcome.quote(), "COUNTERED", null, outcome.reply(),
                outcome.usage());

        log.info("议价会话已创建：sessionNo={} productId={} buyerId={} 首轮报价={}",
                session.getSessionNo(), productId, buyerId, outcome.quote());
        return NegotiationResultVO.of(session.getSessionNo(), "COUNTERED",
                outcome.quote(), null, outcome.reply(), 1);
    }

    // ==================================================================
    // 买家：出价
    // ==================================================================

    /**
     * 买家推进一轮议价：可以带出价，也可以只是说一句话。
     *
     * <h2>⭐ 为什么允许 {@code offerYuan == null}</h2>
     * 这条限制曾经是 {@code offerYuan 必须 > 0}，结果是：<b>买家只能填数字</b>，
     * 那么「你底价多少」「别家更便宜」「我要投诉」这些消息在产品上<b>根本发不出来</b>，
     * 意图识别里 6 类意图永远不会被触发 ——
     * 它就不是"识别得不准"，而是"根本没机会被用上"。
     *
     * <p>所以"只说话不出价"必须是一条合法路径。安全性不靠入口限制来保证，
     * 而靠下面这条规则：<b>只要带了出价，就一定会进授权闸门</b>；
     * 没有出价就永远不会有成交（成交要么用买家出价，要么用我方报价，两者都由闸门裁定）。
     *
     * <p>另一条设计约定：<b>每一条买家发言都会消耗一个议价轮次</b>，无论有没有报价。
     * 因为每一轮都意味着一次真实的策略决策和一次模型调用 ——
     * 成本与风险都发生了。若只对"出价"计数，买家可以用闲聊无限消耗资源。
     */
    @Transactional
    public NegotiationResultVO offer(String sessionNo, BigDecimal offerYuan,
                                     String messageId, String buyerMessage) {
        Long buyerId = JwtInterceptor.getCurrentUserId();
        NegotiationSession session = loadSession(sessionNo);
        if (!session.getBuyerId().equals(buyerId)) {
            throw new BusinessException(403, "无权操作该议价会话");
        }
        if (offerYuan == null && !StringUtils.hasText(buyerMessage)) {
            throw new BusinessException("请填写出价，或说点什么");
        }
        if (offerYuan != null && offerYuan.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("出价必须大于 0");
        }

        return withIdempotency(messageId,
                () -> doOffer(session, offerYuan, messageId, buyerMessage));
    }

    /**
     * 幂等包装器：出价与人工决策共用同一套防重逻辑。
     *
     * <p>抽出来的原因很实际 —— 人工决策这条新路径如果自己再抄一遍
     * <code>setIfAbsent</code> + <code>DuplicateKeyException</code> + 释放 key，
     * 迟早会漏抄某一句（比如忘了捕获 DuplicateKey）。
     * <b>防重这种"错了就是钱的事"的逻辑，只有一份实现才安全。</b>
     */
    private NegotiationResultVO withIdempotency(String messageId,
                                                Supplier<NegotiationResultVO> action) {
        // ── 防重第 1 重：Redis 幂等（快路径）
        //
        // ⚠️ Redis 不可用时【放行】，而不是直接报错。
        //    理由（和类注释里"锁是性能优化、不是正确性保证"是同一条逻辑）：
        //    Redis 这一重只负责"提前挡掉 + 省一次 LLM 调用的钱"，
        //    真正的正确性由第 2 重（轮次 CAS）和第 4 重（唯一索引）保证：
        //      · 同一 messageId 并发重复 → 轮次 CAS 只有一个能成功；
        //      · 同一 messageId 顺序重试 → 唯一索引 uk_message 直接拒绝。
        //    所以放行的代价是"可能多花一次 token 钱"，而不是"写出重复轮次"。
        //    反过来说，如果 Redis 一挂就 500，那 Redis 就从"优化"变成了单点依赖。
        String idemKey = IDEM_PREFIX + messageId;
        boolean firstTime = true;
        try {
            firstTime = Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(30)));
        } catch (Exception e) {
            // 这个降级必须告警：不告警的话，Redis 长期不可用会悄悄把 token 成本翻倍
            log.error("[幂等降级] Redis 不可用，本次跳过幂等快路径，改由轮次 CAS + 唯一索引兜底。err={}",
                    e.getMessage());
        }
        if (!firstTime) {
            throw new BusinessException(409, "请求已处理，请勿重复提交");
        }

        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            // 第 4 重防线命中。走到这里说明前三重有漏网（通常是上面那次 Redis 降级）。
            // 这不是 bug，是兜底生效 —— 但必须打日志，否则你会以为幂等一直很稳。
            log.warn("[幂等兜底] 唯一索引拦截到重复写入：messageId={}", messageId);
            throw new BusinessException(409, "请求已处理，请勿重复提交");
        } catch (RuntimeException e) {
            // 处理失败要释放幂等键，否则用户重试会被自己挡在门外
            releaseIdemKey(idemKey);
            throw e;
        }
    }

    /**
     * 释放幂等键。
     *
     * <p>单独抽出来并把异常吞掉：<b>清理动作失败不能掩盖真正的业务异常</b>。
     * 原来直接调 {@code delete}，Redis 抖一下就会用一个 ConnectException
     * 顶替掉真正的失败原因，排查时看到的报错完全不是根因。
     */
    private void releaseIdemKey(String idemKey) {
        try {
            stringRedisTemplate.delete(idemKey);
        } catch (Exception e) {
            log.warn("[幂等降级] 释放幂等键失败（Redis 不可用），键将在 TTL 到期后自动消失：{}", e.getMessage());
        }
    }

    private NegotiationResultVO doOffer(NegotiationSession session, BigDecimal offerYuan,
                                        String messageId, String buyerMessage) {
        if (!PHASE_BARGAINING.equals(session.getPhase())) {
            throw new BusinessException("当前会话状态为「" + phaseText(session.getPhase()) + "」，无法继续出价");
        }

        int nextRound = session.getRoundNo() + 1;

        // ── 自主范围上限：超过最大轮次就不再自动让步，转人工
        //    注意这里【不消耗】本次出价去算报价 —— 轮次已经用尽，
        //    Agent 不该再给出任何新的价格承诺，否则上限就是假的。
        if (nextRound > maxRounds) {
            BigDecimal lastQuote = session.getCurrentQuote();
            String reason = BargainOutcome.SuspensionReason.ROUND_LIMIT_EXCEEDED.name();
            saveGuardLog(session, reason, offerYuan, lastQuote);
            updateSessionCas(session, nextRound, PHASE_SUSPENDED, lastQuote, offerYuan, null, reason);
            saveRound(session, nextRound, intentClassifier.classify(buyerMessage),
                    offerYuan, lastQuote, "SUSPENDED", messageId, SUSPEND_REPLY);
            log.warn("议价已达最大轮次上限，转人工：sessionNo={} round={} maxRounds={}",
                    session.getSessionNo(), nextRound, maxRounds);
            return new NegotiationResultVO(session.getSessionNo(), "SUSPENDED",
                    lastQuote, null, SUSPEND_REPLY, nextRound, true,
                    suspendReasonText(reason), SUSPEND_REPLY);
        }

        Product product = productMapper.selectById(session.getProductId());
        ProductAuthorization auth = authorizationMapper.selectOne(
                new LambdaQueryWrapper<ProductAuthorization>()
                        .eq(ProductAuthorization::getProductId, session.getProductId()));
        String strategy = auth == null ? BuiltInStrategies.PRODUCTION_STRATEGY : auth.getStrategyName();

        // ── 编排交给 Graph：意图识别 → 定价 → 闸门 → 话术
        GraphOutcome g = executeGraph(session, offerYuan, buyerMessage, nextRound, strategy, product);

        // ── 僵局检测：分精度下本轮报价与上轮相同 → 继续自动让步已无意义
        //    放在 Graph 之外，因为它依赖"上一轮报价"，属于跨轮次的状态判断，
        //    不是单轮内部的计算。
        boolean stalled = "COUNTERED".equals(g.branch())
                && StallDetector.isStalled(Money.ofYuan(session.getCurrentQuote()), Money.ofYuan(g.quote()));
        String branch = stalled ? "STALLED" : g.branch();

        // ── 持久化与留痕（Graph 不碰数据库，全部在这里落）
        //
        //    ⚠️ 五个分支里只有 SUSPENDED 写 t_bargain_guard_log。
        //       因为"拦截 N 次"这个指标的口径是【价格越界】：
        //       REFUSED（套底价被拒）和 ESCALATED（情绪投诉）都不是价格问题，
        //       混进去会让这个数字不再可复现。它们的可追溯性由 round 表承担。
        switch (branch) {
            case "ACCEPTED" -> {
                updateSessionCas(session, nextRound, PHASE_AGREED, g.quote(), offerYuan, g.quote(), null);
                saveRound(session, nextRound, g.intent(), offerYuan, g.quote(), "ACCEPTED", messageId, g.reply(),
                        g.usage());
                log.info("议价达成：sessionNo={} 成交价={} intent={}", session.getSessionNo(), g.quote(), g.intent());
                return new NegotiationResultVO(session.getSessionNo(), "ACCEPTED",
                        g.quote(), g.quote(), g.reply(), nextRound, false, null, null);
            }
            case "SUSPENDED" -> {
                // 越界：挂起转人工 + 留痕 ——「拦截 N 次」这个指标的数据来源
                //
                // ⚠️ 注意这里【刻意不用】 g.reply()。
                // 原因是一个实测发现的 Checkpoint 副作用：
                // 条件边路由到 human_takeover 后，interruptBefore 会挡在该节点之前，
                // 于是 talk 节点根本没执行、reply 这个 key 没有被写入 ——
                // 而 Checkpoint 会把上一轮的 reply 原样带过来，
                // 结果第 2 轮挂起时返回的却是第 1 轮"¥840 给你吧"的话术。
                //
                // 这类问题的特点是：**不报错、逻辑看起来也对，只是内容是旧的**。
                // 教训：「从 Checkpoint 恢复」既是特性也是陷阱 ——
                // 每轮都需要重新计算的 key，不能依赖它自己保持同步。
                //
                // 修法上也顺带厘清了职责：「已转人工」本质是系统通知，不是卖家说的话，
                // 本来就不该由话术生成器来产出。
                String suspendReply = SUSPEND_REPLY;
                saveGuardLog(session, g.suspendReason(), offerYuan, g.quote());
                updateSessionCas(session, nextRound, PHASE_SUSPENDED, g.quote(), offerYuan,
                        null, g.suspendReason());
                saveRound(session, nextRound, g.intent(), offerYuan, g.quote(), "SUSPENDED", messageId, suspendReply);
                log.warn("议价出价越界，已挂起转人工：sessionNo={} reason={} offer={}",
                        session.getSessionNo(), g.suspendReason(), offerYuan);
                return new NegotiationResultVO(session.getSessionNo(), "SUSPENDED",
                        g.quote(), null, suspendReply, nextRound, true,
                        suspendReasonText(g.suspendReason()), "已转人工，卖家本人会尽快回复你");
            }
            case "ESCALATED" -> {
                // 买家情绪投诉 → 挂起转人工。与 SUSPENDED 的区别只在"为什么挂起"：
                // 一个是价格越界（风控），一个是人的问题（体验）。
                // 所以这里不写风控留痕，只落 round（intent + branch 就是完整证据）。
                //
                // 投诉这条路径可能【没带出价】（买家只是骂了一句），此时 last_buyer_offer
                // 要沿用旧值：卖家接管后如果选择"按买家的价放行"，需要一个基准价。
                updateSessionCas(session, nextRound, PHASE_SUSPENDED, g.quote(),
                        offerYuan != null ? offerYuan : session.getLastBuyerOffer(),
                        null, g.suspendReason());
                saveRound(session, nextRound, g.intent(), offerYuan, g.quote(), "ESCALATED", messageId,
                        ESCALATE_REPLY);
                log.warn("买家情绪投诉，已挂起转人工：sessionNo={} intent={}",
                        session.getSessionNo(), g.intent());
                return new NegotiationResultVO(session.getSessionNo(), "ESCALATED",
                        g.quote(), null, ESCALATE_REPLY, nextRound, true,
                        suspendReasonText(g.suspendReason()), "已转人工，卖家本人会尽快回复你");
            }
            case "REFUSED" -> {
                // 套底价被拒：会话【继续】，买家可以接着说他想出的价。
                // 这和 SUSPENDED 的区别很重要 —— 问底价不是越界，不该把决定权交给卖家。
                //
                // ⚠️ last_buyer_offer 传旧值而不是 null：本轮确实没有出价，
                //    但"最近一次出价"是历史事实，不该被一次问价抹掉。
                updateSessionCas(session, nextRound, PHASE_BARGAINING, g.quote(),
                        session.getLastBuyerOffer(), null, null);
                saveRound(session, nextRound, g.intent(), null, g.quote(), "REFUSED", messageId, g.reply(),
                        g.usage());
                log.info("买家询问底价，已按固定话术拒绝透露：sessionNo={} 报价维持 ¥{}",
                        session.getSessionNo(), g.quote());
                return NegotiationResultVO.of(session.getSessionNo(), "REFUSED",
                        g.quote(), null, g.reply(), nextRound);
            }
            case "STALLED" -> {
                updateSessionCas(session, nextRound, PHASE_STALLED, g.quote(), offerYuan, null, null);
                saveRound(session, nextRound, g.intent(), offerYuan, g.quote(), "STALLED", messageId, g.reply(),
                        g.usage());
                return new NegotiationResultVO(session.getSessionNo(), "STALLED",
                        g.quote(), null, g.reply(), nextRound, false, null,
                        "价格已经到底了，再谈下去也不会有变化");
            }
            default -> {
                updateSessionCas(session, nextRound, PHASE_BARGAINING, g.quote(), offerYuan, null, null);
                saveRound(session, nextRound, g.intent(), offerYuan, g.quote(), "COUNTERED", messageId, g.reply(),
                        g.usage());
                return NegotiationResultVO.of(session.getSessionNo(), "COUNTERED",
                        g.quote(), null, g.reply(), nextRound);
            }
        }
    }

    /** 买家接受当前报价。 */
    @Transactional
    public NegotiationResultVO accept(String sessionNo, String messageId) {
        NegotiationSession session = loadSession(sessionNo);
        if (session.getCurrentQuote() == null) {
            throw new BusinessException("当前没有可接受的报价");
        }
        // 复用自己的出价路径：出价 == 我方报价 → 闸门判定为 Accepted
        return offer(sessionNo, session.getCurrentQuote(), messageId, "行，我要了");
    }

    // ==================================================================
    // 成本报表（要点 5）
    // ==================================================================

    /**
     * 成本汇总 —— 一条 SQL 聚合，不遍历、不估算。
     *
     * <p>这个接口存在的意义不是"给前端用"，而是让
     * <b>"单次议价成本 ¥X"这个数字随时可以被重新算一遍</b>。
     * 数字如果是手写在 README 里的，它就只是个说法；
     * 能由数据重新算出来的，才叫可复现。
     */
    public CostSummaryVO costSummary() {
        Map<String, Object> row = roundMapper.selectCostSummary();
        if (row == null) {
            row = Map.of();
        }
        return CostSummaryVO.of(
                toLong(row.get("sessionCount")),
                toLong(row.get("roundCount")),
                toLong(row.get("promptTokens")),
                toLong(row.get("completionTokens")),
                toDecimal(row.get("totalCost")),
                (String) row.get("modelName"),
                // 报表里必须标出"这个成本是按哪个模型的单价算的"，
                // 否则换了模型/改了单价之后，历史数字就再也解释不清了。
                tokenCostCalculator.rateDescription((String) row.get("modelName")));
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        return ((Number) v).longValue();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return (v instanceof BigDecimal b) ? b : new BigDecimal(String.valueOf(v));
    }

    // ==================================================================
    // 卖家：人工接管后的决策（HITL 断点续跑）
    // ==================================================================

    /**
     * 人工接管后的决策 —— HITL 的另一半。
     *
     * <p>前半程是 {@link #offer}：越界或超轮次 → 挂起、交回人。
     * 这里接收人的决定，并让 Graph <b>从断点继续跑</b>。
     *
     * <h2>为什么走 Graph 的 resume，而不是直接拼一句回复</h2>
     * 挂起时流程停在 {@code human_takeover} <b>之前</b>，
     * 后面的"生成话术"这一步还没执行。用
     * {@code resume(HumanFeedback(data, nextNodeId))} 把人工决定注入状态、
     * 并指定从 talk 节点继续，就能<b>复用同一条话术生成链路</b>
     * （含数值一致性校验与模板兜底，见 {@link #resumeGraphForHumanDecision}）。
     * 直接拼字符串会绕过校验 —— 那正是"用纪律替代架构"的反例。
     *
     * <h2>权限</h2>
     * 只有该商品的卖家能处理。这不是形式主义：
     * 放行价直接决定成交金额，谁能点这个按钮就是谁能改价格。
     */
    @Transactional
    public NegotiationResultVO humanDecision(String sessionNo, boolean approve,
                                             BigDecimal priceYuan, String messageId) {
        Long sellerId = JwtInterceptor.getCurrentUserId();
        NegotiationSession session = loadSession(sessionNo);
        if (!session.getSellerId().equals(sellerId)) {
            throw new BusinessException(403, "只有该商品的卖家可以处理这次接管");
        }
        if (!PHASE_SUSPENDED.equals(session.getPhase())) {
            throw new BusinessException("当前会话状态为「" + phaseText(session.getPhase()) + "」，无需人工处理");
        }
        if (!StringUtils.hasText(messageId)) {
            throw new BusinessException("缺少 messageId（幂等键）");
        }

        return withIdempotency(messageId,
                () -> doHumanDecision(session, approve, priceYuan, messageId));
    }

    private NegotiationResultVO doHumanDecision(NegotiationSession session, boolean approve,
                                                BigDecimal priceYuan, String messageId) {
        int nextRound = session.getRoundNo() + 1;
        BigDecimal lastOffer = session.getLastBuyerOffer();

        if (!approve) {
            updateSessionCas(session, nextRound, PHASE_REJECTED, session.getCurrentQuote(),
                    lastOffer, null, null);
            saveRound(session, nextRound, "HUMAN_REJECT", lastOffer, session.getCurrentQuote(),
                    "REJECTED", messageId, REJECT_REPLY);
            log.info("人工拒绝：sessionNo={}", session.getSessionNo());
            return new NegotiationResultVO(session.getSessionNo(), "REJECTED",
                    session.getCurrentQuote(), null, REJECT_REPLY, nextRound, false, null,
                    "卖家没有接受这个价");
        }

        // 放行价：没填就用触发挂起的那次买家出价（最常见的情况就是"按买家的价卖了"）
        BigDecimal agreed = priceYuan != null ? priceYuan : lastOffer;
        if (agreed == null || agreed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("无法确定放行价格，请在请求里带上 price");
        }

        // 人工放行价【允许】低于底价：决定权已回到卖家本人，
        // 卖家愿意低于自己设的底价卖，是合法的业务决策 ——
        // 系统在这里再拦一道，就等于替卖家做决定，HITL 也就没意义了。
        // 但必须留痕：这是唯一一条突破授权下限的成交路径，事后要能审计。
        AuthorizedRange range = adapter.toRange(session);
        if (Money.ofYuan(agreed).compareTo(range.floorPrice()) < 0) {
            saveGuardLog(session,
                    BargainOutcome.SuspensionReason.HUMAN_RELEASED_BELOW_FLOOR.name(), agreed, agreed);
            log.warn("[人工放行] 价格低于授权下限，已留痕：sessionNo={} price={}",
                    session.getSessionNo(), agreed);
        }

        String reply = resumeGraphForHumanDecision(session, agreed, nextRound);
        // 人工放行这条路径不走 Graph 的 talk 节点，所以要在 Service 里自己取走用量
        LlmUsageRecorder.TokenUsage usage = usageRecorder.drain();

        updateSessionCas(session, nextRound, PHASE_AGREED, agreed, lastOffer, agreed, null);
        saveRound(session, nextRound, "HUMAN_APPROVE", lastOffer, agreed, "ACCEPTED", messageId, reply, usage);
        log.info("人工放行成交：sessionNo={} 成交价={}", session.getSessionNo(), agreed);
        return new NegotiationResultVO(session.getSessionNo(), "ACCEPTED",
                agreed, agreed, reply, nextRound, false, null, "已按这个价达成，可以去下单了");
    }

    /**
     * 从断点续跑 Graph，拿到人工放行后的话术。
     *
     * <p><b>resume 失败了不能让整笔谈判卡住。</b>兜底是"直接调话术生成器"，
     * 它自带数值一致性校验与模板兜底，所以安全性不依赖 resume 是否成功 ——
     * 这和"LLM 挂了退回模板"是同一条思路：<b>能降级的功能不要做成硬依赖。</b>
     */
    private String resumeGraphForHumanDecision(NegotiationSession session, BigDecimal agreed, int round) {
        Product product = productMapper.selectById(session.getProductId());
        Integer condition = product == null || product.getProductCondition() == null
                ? 0 : product.getProductCondition();
        String title = product == null ? "" : product.getTitle();

        try {
            Map<String, Object> feedback = new HashMap<>();
            feedback.put(NegotiationGraphConfig.KEY_BRANCH, "ACCEPTED");
            feedback.put(NegotiationGraphConfig.KEY_QUOTE, agreed);
            feedback.put(NegotiationGraphConfig.KEY_ROUND, round);
            feedback.put("productTitle", title);
            feedback.put("condition", condition);
            feedback.put("listPrice", session.getSnapshotListPrice());

            RunnableConfig config = RunnableConfig.builder().threadId(session.getSessionNo()).build();
            Optional<OverAllState> resumed = negotiationGraph.resume(
                    new OverAllState.HumanFeedback(feedback, NegotiationGraphConfig.NODE_TALK), config);

            if (resumed.isPresent()) {
                OverAllState state = resumed.get();
                String reply = state.value(NegotiationGraphConfig.KEY_REPLY, "");
                BigDecimal resumedQuote = (BigDecimal) state
                        .value(NegotiationGraphConfig.KEY_QUOTE).orElse(null);

                // ⚠️ 必须校验价格，不能直接采信。原因和 SUSPENDED 分支那个坑同源：
                //    Checkpoint 会把上一轮的值原样带过来，而"每轮都要重算"的 key
                //    不会自己保持同步。实测放过一次：放行价 450，话术却说"¥680"。
                //    宁可多花一次生成成本，也不能把错的价格发给买家。
                if (talkGenerator.priceConsistent(reply, agreed)) {
                    return reply;
                }
                log.warn("[HITL] resume 回来的话术与放行价不一致（resumeQuote={} 期望={} reply={}），改用生成器重出",
                        resumedQuote, agreed, reply);
            } else {
                log.warn("[HITL] Graph resume 未返回状态，改用话术生成器直出：sessionNo={}",
                        session.getSessionNo());
            }
        } catch (Exception e) {
            log.warn("[HITL] Graph resume 失败，降级为直接生成话术：{}", e.getMessage());
        }

        return talkGenerator.generate(new NegotiationTalkGenerator.TalkContext(
                title, condition, session.getSnapshotListPrice(), agreed, "", "ACCEPTED", round,
                // 人工放行是"卖家亲自拍板"，跟这轮文本无关 ——
                // 这里传 UNKNOWN 而不是猜一个意图，避免把噪音写进话术上下文。
                IntentClassifier.Intent.UNKNOWN.name()));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public NegotiationSessionVO sessionState(String sessionNo) {
        return toSessionVO(loadSession(sessionNo));
    }

    public List<NegotiationRound> rounds(String sessionNo) {
        NegotiationSession session = loadSession(sessionNo);
        return roundMapper.selectList(new LambdaQueryWrapper<NegotiationRound>()
                .eq(NegotiationRound::getSessionId, session.getId())
                .orderByAsc(NegotiationRound::getRoundNo));
    }

    // ==================================================================
    // Graph 执行
    // ==================================================================

    /**
     * 执行议价状态图，返回本轮结果。
     *
     * <p>{@code threadId = sessionNo} 让同一会话的 Checkpoint 序列绑在一起 ——
     * 这是"状态按会话持久化"的落点，也是 HITL 断点续跑能找回上下文的前提。
     */
    private GraphOutcome executeGraph(NegotiationSession session, BigDecimal offerYuan,
                                      String buyerMessage, int round, String strategy, Product product) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(NegotiationGraphConfig.KEY_BUYER_MESSAGE, buyerMessage == null ? "" : buyerMessage);
        // ⚠️ 授权区间（含底价）只在内存中传递；Checkpoint 用的是 MemorySaver，不落盘。
        //    换 RedisSaver 之前必须先把底价移出 state，否则它会顺着持久化流到第二个地方。
        inputs.put(NegotiationGraphConfig.KEY_RANGE, adapter.toRange(session));
        inputs.put(NegotiationGraphConfig.KEY_STRATEGY, strategy);
        inputs.put(NegotiationGraphConfig.KEY_ROUND, round);
        // 上一轮报价：拒绝套底价那一轮要沿用旧价，不能因为被问一次就降一次价
        inputs.put(NegotiationGraphConfig.KEY_PREV_QUOTE, session.getCurrentQuote());
        if (offerYuan != null) {
            inputs.put(NegotiationGraphConfig.KEY_OFFER, offerYuan);
        }
        inputs.put("listPrice", session.getSnapshotListPrice());
        if (product != null) {
            inputs.put("productTitle", product.getTitle());
            inputs.put("condition", product.getProductCondition() == null ? 0 : product.getProductCondition());
        } else {
            inputs.put("condition", 0);
        }

        RunnableConfig config = RunnableConfig.builder().threadId(session.getSessionNo()).build();
        OverAllState state = negotiationGraph.invoke(inputs, config)
                .orElseThrow(() -> new BusinessException("议价流程执行失败（未返回状态）"));

        String branch = state.value(NegotiationGraphConfig.KEY_BRANCH, "COUNTERED");
        BigDecimal quote = (BigDecimal) state.value(NegotiationGraphConfig.KEY_QUOTE).orElse(offerYuan);
        String reply = state.value(NegotiationGraphConfig.KEY_REPLY, "");
        String suspendReason = (String) state.value(NegotiationGraphConfig.KEY_SUSPEND_REASON).orElse(null);
        // 意图识别结果顺路带出来落库：t_negotiation_round.intent 之前一直写死 "OFFER"，
        // 而意图其实早就算好了 —— 那正是"算了不用"的典型症状（见 IntentRoutingTest）。
        String intent = state.value(NegotiationGraphConfig.KEY_INTENT,
                IntentClassifier.Intent.UNKNOWN.name());

        // 成本埋点：talk 节点把用量写进 state，这里取出来落库。
        // SUSPENDED 分支不会执行 talk 节点，所以这里自然是 0 —— 那是真实情况，不补零。
        int promptTokens = state.value(NegotiationGraphConfig.KEY_PROMPT_TOKENS, 0);
        int completionTokens = state.value(NegotiationGraphConfig.KEY_COMPLETION_TOKENS, 0);
        String model = (String) state.value(NegotiationGraphConfig.KEY_MODEL).orElse(null);

        if (quote == null) {
            throw new BusinessException("议价流程未产出报价");
        }
        return new GraphOutcome(branch, quote, reply, suspendReason, intent,
                new LlmUsageRecorder.TokenUsage(promptTokens, completionTokens, model));
    }

    /** 一轮编排的结果（Graph 输出到 Service 的载体）。 */
    private record GraphOutcome(String branch, BigDecimal quote, String reply, String suspendReason,
                                String intent, LlmUsageRecorder.TokenUsage usage) {
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private NegotiationResultVO currentState(NegotiationSession session) {
        String reply = switch (session.getPhase()) {
            case PHASE_AGREED -> "这笔我们之前已经谈好了，" + session.getAgreedPrice() + " 元，你直接拍就行。";
            case PHASE_SUSPENDED -> "之前的报价我这边还在处理，稍等一下。";
            default -> "我们接着聊，" + session.getCurrentQuote() + " 元你看可以吗？";
        };
        return NegotiationResultVO.of(session.getSessionNo(), session.getPhase(),
                session.getCurrentQuote(), session.getAgreedPrice(), reply,
                session.getRoundNo() == null ? 1 : session.getRoundNo());
    }

    private NegotiationSession findSession(Long productId, Long buyerId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<NegotiationSession>()
                .eq(NegotiationSession::getProductId, productId)
                .eq(NegotiationSession::getBuyerId, buyerId));
    }

    private NegotiationSession loadSession(String sessionNo) {
        NegotiationSession session = sessionMapper.selectOne(new LambdaQueryWrapper<NegotiationSession>()
                .eq(NegotiationSession::getSessionNo, sessionNo));
        if (session == null) {
            throw new BusinessException("议价会话不存在");
        }
        return session;
    }

    /**
     * 会话状态更新（带轮次乐观锁）。
     *
     * <p>{@code WHERE round_no = 读到的值} 这一句就是<b>「轮次序号单调校验」</b>：
     * 两个并发请求读到同一个 roundNo，只有一个能把它改成 nextRound，
     * 另一个更新 0 行、收到 409。即使前面的幂等失效，也写不出重复轮次。
     */
    private void updateSessionCas(NegotiationSession session, int nextRound, String phase,
                                  BigDecimal quote, BigDecimal buyerOffer,
                                  BigDecimal agreedPrice, String suspendReason) {
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<NegotiationSession>()
                .eq(NegotiationSession::getId, session.getId())
                .eq(NegotiationSession::getRoundNo, session.getRoundNo())
                .set(NegotiationSession::getRoundNo, nextRound)
                .set(NegotiationSession::getPhase, phase)
                .set(NegotiationSession::getCurrentQuote, quote)
                .set(NegotiationSession::getLastBuyerOffer, buyerOffer)
                .set(NegotiationSession::getAgreedPrice, agreedPrice)
                .set(NegotiationSession::getSuspendReason, suspendReason));
        if (updated == 0) {
            throw new BusinessException(409, "会话已被其他请求更新，请刷新后重试");
        }
    }

    private void saveRound(NegotiationSession session, int roundNo, String intent,
                           BigDecimal buyerOffer, BigDecimal counterQuote, String branch,
                           String messageId, String reply) {
        saveRound(session, roundNo, intent, buyerOffer, counterQuote, branch, messageId, reply,
                new LlmUsageRecorder.TokenUsage(0, 0, null));
    }

    /**
     * 落一轮记录（含 token 成本埋点）。
     *
     * <p>成本数字能不能站住脚，全靠这里：
     * <b>每一轮的 token 数都是真实调用返回的，不是估算的</b>。
     */
    private void saveRound(NegotiationSession session, int roundNo, String intent,
                           BigDecimal buyerOffer, BigDecimal counterQuote, String branch,
                           String messageId, String reply, LlmUsageRecorder.TokenUsage usage) {
        NegotiationRound round = new NegotiationRound();
        round.setSessionId(session.getId());
        round.setRoundNo(roundNo);
        round.setIntent(intent);
        round.setBuyerOffer(buyerOffer);
        round.setCounterQuote(counterQuote);
        round.setBranch(branch);
        round.setMessageId(messageId);
        round.setAgentReply(reply);
        round.setPromptTokens(usage.promptTokens());
        round.setCompletionTokens(usage.completionTokens());
        // ⚠️ 必须带上模型名：做了分级路由之后，同一轮里可能混用两档模型，
        //    用单一单价算成本会系统性低估（强模型那部分被按轻量价算）。
        round.setTokenCost(tokenCostCalculator.cost(
                usage.promptTokens(), usage.completionTokens(), usage.model()));
        round.setModelName(usage.model());
        roundMapper.insert(round);
    }

    private void saveGuardLog(NegotiationSession session, String reason,
                              BigDecimal offer, BigDecimal counterQuote) {
        BargainGuardLog logRow = new BargainGuardLog();
        logRow.setSessionId(session.getId());
        logRow.setProductId(session.getProductId());
        logRow.setBuyerId(session.getBuyerId());
        logRow.setReason(reason);
        logRow.setOfferedPrice(offer);
        logRow.setCounterQuote(counterQuote);
        // detail 只描述事实，绝不写价格下限（否则留痕表本身变成泄漏通道）
        logRow.setDetail(suspendReasonText(reason));
        guardLogMapper.insert(logRow);
    }

    /** 把挂起原因的枚举名转成展示文案。 */
    private String suspendReasonText(String reasonName) {
        if (reasonName == null) {
            return null;
        }
        try {
            return BargainOutcome.SuspensionReason.valueOf(reasonName).description();
        } catch (IllegalArgumentException e) {
            return "议价流程需要人工介入";
        }
    }

    private NegotiationSessionVO toSessionVO(NegotiationSession session) {
        Product product = productMapper.selectById(session.getProductId());
        Long guardCount = guardLogMapper.selectCount(new LambdaQueryWrapper<BargainGuardLog>()
                .eq(BargainGuardLog::getSessionId, session.getId()));
        return new NegotiationSessionVO(
                session.getSessionNo(),
                session.getProductId(),
                product == null ? "已删除" : product.getTitle(),
                session.getSnapshotListPrice(),
                session.getCurrentQuote(),
                session.getRoundNo(),
                session.getPhase(),
                phaseText(session.getPhase()),
                session.getAgreedPrice(),
                session.getOrderId(),
                guardCount);
    }

    private String phaseText(String phase) {
        if (phase == null) {
            return "未知";
        }
        return switch (phase) {
            case PHASE_BARGAINING -> "议价中";
            case PHASE_AGREED -> "已达成";
            case PHASE_SUSPENDED -> "已转人工";
            case PHASE_STALLED -> "已到底价";
            case PHASE_REJECTED -> "卖家已拒绝";
            default -> phase;
        };
    }

    /** 上架天数（时间折扣的输入）。createTime 为空按 0 处理。 */
    private int daysListed(LocalDateTime createTime) {
        if (createTime == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(createTime, LocalDateTime.now());
        return (int) Math.max(0, Math.min(days, 365));
    }
}
