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
    private BigDecimal productPrice;
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