package com.example.unitrade.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品详情返回对象（完整字段）
 * 实现 Serializable 接口以支持 Redis 缓存序列化
 */
@Data
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 发布者ID */
    private Long userId;

    /** 发布者昵称 */
    private String nickname;

    /** 发布者头像 */
    private String avatarUrl;

    /** 分类名称 */
    private String categoryName;

    /** 商品标题 */
    private String title;

    /** 商品描述 */
    private String description;

    /** 售价 */
    private BigDecimal price;

    /** 原价 */
    private BigDecimal originalPrice;

    /** 成色 */
    private Integer productCondition;

    /** 图片路径列表（逗号分隔转成数组） */
    private List<String> images;

    /** 物流方式 */
    private Integer shippingType;
    /** 邮费 */
    private BigDecimal shippingFee;
    /** 商品状态 */
    private Integer status;

    /** 浏览次数 */
    private Integer viewCount;

    /** 当前登录用户是否已收藏 */
    private Boolean isFavorite;

    private LocalDateTime createTime;
}