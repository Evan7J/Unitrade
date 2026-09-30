package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 议价幂等去重（t_negotiation_idempotency）。
 *
 * <h2>它在「四重防重」里的位置：第 1 重（最外层的前置过滤器）</h2>
 * <pre>
 *   messageId 幂等去重     ← Redis SET NX EX（快）+ 本表唯一索引（兜底）
 *   轮次序号单调校验        ← roundNo 必须 = 当前 +1
 *   会话级分布式锁          ← Redis 锁，避免同一会话并发处理
 *   数据库唯一索引          ← t_negotiation_round 的两条唯一键
 * </pre>
 *
 * <p><b>为什么要 Redis 之外再落一张表</b>：Redis 是"快"但不是"久" ——
 * 它可能被 flush、可能因内存淘汰策略丢 key、也可能压根没连上（本项目限流就是 fail-open 的）。
 * 幂等是<b>正确性要求</b>，不能只押在一个可能丢数据的组件上。
 * 两者分工是：Redis 挡掉 99% 的重复请求（不落库、便宜），
 * 本表在事务里确保同一 messageId 绝不产生第二条业务记录。
 */
@Data
@TableName("t_negotiation_idempotency")
public class NegotiationIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息 ID（唯一索引）。 */
    private String messageId;

    private Long sessionId;

    /** 首次处理的结果摘要，便于重复请求直接返回而不是报错。 */
    private String resultSummary;

    private LocalDateTime createTime;
}
