package com.example.unitrade.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表返回对象（精简字段，不返回描述等大字段，减少传输量）
 */
@Data
public class ProductListVO {

    private Long id;

    /** 商品标题 */
    private String title;

    /** 售价 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 成色 */
    private Integer productCondition;

    /** 第一张图片（封面） */
    private String coverImage;

    /** 发布者昵称 */
    private String nickname;

    /** 发布者头像 */
    private String avatarUrl;

    /** 浏览次数 */
    private Integer viewCount;

    /** 物流方式 */
    private Integer shippingType;
    /** 邮费 */
    private BigDecimal shippingFee;
    /** 发布时间 */
    private LocalDateTime createTime;
}