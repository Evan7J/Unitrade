package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 议价轮次明细（t_negotiation_round）。
 *
 * <h2>这张表承担了「四重防重」里的两重（都在数据库约束层）</h2>
 * <ol>
 *   <li>{@code uk_session_round (session_id, round_no)} —— 同一会话同一轮次只能有一条。
 *       重复出价即使穿过了应用层的幂等与轮次校验，也会在落库时被唯一索引拦下；</li>
 *   <li>{@code uk_message (message_id)} —— 同一 messageId 只能落一条，
 *       与 Redis 的 {@code SET NX EX} 构成"快路径 + 最终兜底"的双保险。</li>
 * </ol>
 *
 * <p><b>这两条约束的顺序和位置很关键</b>：它们不在"业务逻辑里"，
 * 而在存储引擎层。因为应用层的检查（先查后写）在并发下必然存在窗口期，
 * 只有数据库的唯一性检查是原子的。
 *
 * <h2>为什么每一轮都要落库</h2>
 * 「输出可追溯的议价记录」是需求的一部分：买家问"你刚才不是报了 720 吗"，
 * 系统要能拿出每一轮的意图、出价、还价与分支，而不是只有最终价。
 */
@Data
@TableName("t_negotiation_round")
public class NegotiationRound {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 轮次序号，从 1 开始，必须单调 +1。 */
    private Integer roundNo;

    /** 意图：ASK_PRICE / OFFER / PROBE_FLOOR / PRESSURE / CONDITION / CHITCHAT / COMPLAINT / CONFIRM / UNKNOWN */
    private String intent;

    /** 买家本轮出价。 */
    private BigDecimal buyerOffer;

    /** 我方本轮还价。 */
    private BigDecimal counterQuote;

    /** 分支：ACCEPTED / COUNTERED / SUSPENDED / ESCALATED / REFUSED / STALLED（与 BargainOutcome.branchName() 一致）。 */
    private String branch;

    /** 客户端消息 ID（幂等去重键）。 */
    private String messageId;

    /** AI 生成的话术（已通过数值一致性校验）。 */
    private String agentReply;

    // ══════════ token 成本埋点（要点 5）══════════
    //
    // 三个字段一起落库，缺任何一个成本数字都不可追溯：
    //   · 只有 tokenCost → 不知道是怎么算出来的（单价改了就对不上）
    //   · 只有 token 数  → 不知道用的是哪个模型（换模型后无法解释成本变化）
    //   · 只有 modelName → 算不出钱

    /** 本轮输入 token 数（真实调用返回，不是估算）。 */
    private Integer promptTokens;

    /** 本轮输出 token 数（真实调用返回，不是估算）。 */
    private Integer completionTokens;

    /** 本轮实际调用的模型名 —— 换模型后历史数据仍知道是哪次。 */
    private String modelName;

    /** 本轮 LLM 调用成本（元），用于"单次议价 token 成本"的核算。 */
    private BigDecimal tokenCost;

    private LocalDateTime createTime;
}
