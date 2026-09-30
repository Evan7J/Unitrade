package com.example.unitrade.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单返回对象
 * 包含订单信息 + 商品信息 + 买家卖家信息
 */
@Data
public class OrderVO {

    private Long id;

    /** 买家信息 */
    private Long buyerId;
    private String buyerNickname;
    private String buyerPhone;

    /** 卖家信息 */
    private Long sellerId;
    private String sellerNickname;
    private String sellerPhone;

    /** 商品信息 */
    private Long productId;
    private String productTitle;

    /**
     * 成交金额。
     *
     * <p>字段名保持不变（前端在用），但<b>取值来源已经变了</b>：
     * 从原来的"实时查 t_product.price"改为"读订单快照 deal_price"。
     * 这样卖家后续改价、下架甚至删除商品，都不会篡改历史订单的金额 ——
     * 订单记录的是已发生的事实，不该随后续编辑而变。
     */
    private BigDecimal productPrice;

    /** 下单时的商品挂牌价（快照），用于展示"原价划线价"与议价让利幅度 */
    private BigDecimal originPrice;

    private String productCover;

    /** 订单状态 */
    private Integer status;
    /** 状态文字（前端展示用） */
    private String statusText;

    /** 取消/退款原因 */
    private String cancelReason;

    /** 时间节点 */
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime shipTime;
    private LocalDateTime completeTime;
}