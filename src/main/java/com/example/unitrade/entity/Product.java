package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类，对应数据库 t_product 表
 *
 * 关键字段说明：
 * status（商品状态）：
 *   1 = 在售（可以浏览和下单）
 *   2 = 已售出（有人下单后锁定，防止一物多卖）
 *   3 = 已下架（卖家主动下架）
 *
 * condition（成色）：
 *   1 = 全新
 *   2 = 几乎全新
 *   3 = 轻微使用痕迹
 *   4 = 明显使用痕迹
 */
@Data
@TableName("t_product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布者（卖家）ID */
    private Long userId;

    /** 分类ID */
    private Long categoryId;

    /** 商品标题 */
    private String title;

    /** 商品描述 */
    private String description;

    /** 售价，用 BigDecimal 保证精度，避免浮点数计算误差 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 成色：1全新 2几乎全新 3轻微使用 4明显使用 */
    private Integer productCondition;

    /** 图片路径，多张用逗号分隔 */
    private String images;

    /** 物流方式：1无需邮寄(面交) 2付邮邮寄 3包邮 */
    private Integer shippingType;

    /** 邮费（当 shippingType=2 时） */
    private BigDecimal shippingFee;

    /** 商品状态：1在售 2已售出 3已下架 */
    private Integer status;

    /** 浏览次数 */
    private Integer viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}