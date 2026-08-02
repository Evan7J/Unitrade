package com.example.unitrade.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 编辑商品请求参数
 * 和发布类似，但多了 id 字段（指定要修改哪个商品）
 */
@Data
public class ProductUpdateDTO {

    /** 商品ID */
    private Long id;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer productCondition;

    private Long categoryId;

    private Integer shippingType;
    private BigDecimal shippingFee;
    private String images;
}
