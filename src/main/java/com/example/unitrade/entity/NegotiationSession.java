package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 议价会话（t_negotiation_session）。
 *
 * <h2>核心设计：授权区间「快照」</h2>
 * 会话创建时把授权区间（挂牌价 / 底价 / 上架天数）整体快照进来，
 * 并记录 {@link #authVersion} 标识它对应哪一版授权。
 * 之后卖家改底价，只影响<b>新</b>会话，进行中的会话继续用旧快照 ——
 * 这样"当时谈的是多少"永远可回溯，议价记录才是可信的证据。
 *
 * <h2>状态机（phase）</h2>
 * <pre>
 *   BARGAINING ──成交──→ AGREED
 *       │  ├──越界/超轮次──→ SUSPENDED ──人工放行──→ BARGAINING
 *       │  └──分精度僵局───→ STALLED
 *       └──买家放弃/商品售出→ CLOSED
 * </pre>
 * 注意 {@code SUSPENDED} 是<b>暂停态不是终态</b>：
 * 人工处理完之后要能回到 BARGAINING 继续，否则买家永远等不到回复。
 * （对应讲义第 5 讲"把暂停态当终态"那个坑。）
 */
@Data
@TableName("t_negotiation_session")
public class NegotiationSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外暴露的会话编号（不暴露自增主键，避免被枚举）。 */
    private String sessionNo;

    private Long productId;

    private Long buyerId;

    private Long sellerId;

    /** 会话创建时的授权版本号（快照标识）。 */
    private Integer authVersion;

    /** 快照：挂牌价。 */
    private BigDecimal snapshotListPrice;

    /**
     * 快照：底价。
     *
     * <p>{@link JsonIgnore} —— 底价绝不能出现在任何接口响应里。
     * 这个字段只服务于服务端的闸门校验。
     */
    @JsonIgnore
    private BigDecimal snapshotFloorPrice;

    /** 快照：上架天数（时间折扣的输入）。 */
    private Integer snapshotDaysListed;

    /** 会话状态：BARGAINING / AGREED / SUSPENDED / STALLED / CLOSED */
    private String phase;

    /** 当前轮次（单调递增，防重投的判据之一）。 */
    private Integer roundNo;

    /** 我方当前报价。 */
    private BigDecimal currentQuote;

    /** 买家最近一次出价。 */
    private BigDecimal lastBuyerOffer;

    /** 成交价（达成时写入）。 */
    private BigDecimal agreedPrice;

    /** 成交后生成的订单 ID。 */
    private Long orderId;

    /** 挂起原因（SUSPENDED 时非空）。 */
    private String suspendReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
