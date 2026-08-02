package com.example.unitrade.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 发布商品请求参数
 *
 * 前端发来的 JSON 示例：
 * {
 *   "title": "iPhone 15 128G 黑色",
 *   "description": "去年双十一买的，用了一年，无划痕",
 *   "price": 3999.00,
 *   "originalPrice": 5999.00,
 *   "productCondition": 3,
 *   "categoryId": 1,
 *   "images": "/uploads/abc.jpg,/uploads/def.jpg"
 * }
 */
@Data
public class ProductPublishDTO {

    /** 商品标题 */
    private String title;

    /** 商品描述 */
    private String description;

    /** 售价 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 成色：1全新 2几乎全新 3轻微使用 4明显使用 */
    private Integer productCondition;

    /** 分类ID */
    private Long categoryId;

    /** 物流方式：1无需邮寄 2付邮邮寄 3包邮 */
    private Integer shippingType;

    /** 邮费（shippingType=2 时必填） */
    private BigDecimal shippingFee;

    /** 图片路径，多张逗号分隔 */
    private String images;
}