package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 议价风控留痕（t_bargain_guard_log）。
 *
 * <h2>它是「拦截 13 次、误放 0 次」这个指标的物理来源</h2>
 * 简历上写「回放 200 条对抗性议价会话，越界出价拦截 13 次、误放 0 次」，
 * 面试官一定会追问"13 次是哪 13 次？举一个例子"。
 * 这张表就是回答那个问题的唯一依据 —— 每一次拦截都留下：
 * <ul>
 *   <li>谁（buyer_id）、在哪个商品上（product_id）、哪一轮触发；</li>
 *   <li>触发了什么原因（reason）；</li>
 *   <li>当时买家出了多少、我们报多少（offered_price / counter_quote）。</li>
 * </ul>
 *
 * <h2>为什么 detail 里绝不能写底价</h2>
 * 留痕表天然会被后台看到、被导出、被贴进工单。
 * 如果 detail 图省事写上"买家出价低于底价 600"，底价就顺着日志泄漏了。
 * 所以这里的约定是：<b>只记"低于授权下限"这个事实，不记下限本身</b>。
 */
@Data
@TableName("t_bargain_guard_log")
public class BargainGuardLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long productId;

    private Long buyerId;

    /** 挂起/拦截原因，取值同 BargainOutcome.SuspensionReason。 */
    private String reason;

    /** 触发拦截的出价。 */
    private BigDecimal offeredPrice;

    /** 拦下时的我方报价（用于事后复盘买家试探的力度）。 */
    private BigDecimal counterQuote;

    /** 补充说明。约定：只描述事实，不含任何价格下限信息。 */
    private String detail;

    private LocalDateTime createTime;
}
