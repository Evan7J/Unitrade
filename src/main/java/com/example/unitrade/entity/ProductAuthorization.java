package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品议价授权区间（t_product_authorization）—— 卖家底价在数据库里的唯一落点。
 *
 * <h2>为什么底价要单独一张表</h2>
 * {@code t_product} 是「公开商品事实」，被商品列表、详情、搜索大量读取。
 * 底价一旦混进去，任何一次 {@code selectById} 回来、任何一次误把实体当返回值，
 * 都可能把它带出去。单独一张表 + 只有议价链路才查它 = 把敏感数据的读取面缩到最小。
 *
 * <h2>version 字段的作用</h2>
 * 卖家中途改底价时 {@code version + 1}，但<b>进行中的会话继续用创建时的快照</b>。
 * 否则同一场谈判里底价会变，议价记录不可追溯，事后无法复盘"当时到底谈的是多少"。
 */
@Data
@TableName("t_product_authorization")
public class ProductAuthorization {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Long sellerId;

    /**
     * 卖家底价（授权下限）。
     *
     * <p>{@link JsonIgnore} 是<b>纵深防御</b>：即使哪天有人图省事把这个实体直接当接口返回值，
     * Jackson 也不会把它序列化出去。类型边界（ArchUnit）防的是"代码里能不能拿到"，
     * 这个注解防的是"拿到了会不会被吐出去"，两者互补。
     */
    @JsonIgnore
    private BigDecimal floorPrice;

    /** 定价策略名，默认 composed（首轮锚定 + 逐轮衰减 + 时间折扣）。 */
    private String strategyName;

    /** 授权版本号；卖家改价时 +1，只对新会话生效。 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
