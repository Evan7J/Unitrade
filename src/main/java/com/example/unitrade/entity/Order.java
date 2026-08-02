package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单实体类，对应数据库 t_order 表
 *
 * 订单状态流转（参考闲鱼交易流程）：
 *
 * 买家下单 → 1.待付款 → 买家付款 → 2.已付款 → 卖家发货 → 3.已发货 → 买家收货 → 4.已完成
 *                ↓                      ↓                      ↓
 *            5.已取消              6.退款中→7.已退款       6.退款中→7.已退款
 *
 * 状态说明：
 *   1 = 待付款（买家下单，30分钟内需付款，超时自动取消）
 *   2 = 已付款（买家已付款，等待卖家发货/见面交易）
 *   3 = 已发货（卖家已发货/已约定见面）
 *   4 = 已完成（买家确认收货，交易完成）
 *   5 = 已取消（订单取消，商品恢复在售）
 *   6 = 退款中（买家申请退款，等待卖家处理）
 *   7 = 已退款（退款完成，商品恢复在售）
 */
@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 买家ID */
    private Long buyerId;

    /** 卖家ID */
    private Long sellerId;

    /** 商品ID */
    private Long productId;

    /** 订单状态：1待付款 2已付款 3已发货 4已完成 5已取消 6退款中 7已退款 */
    private Integer status;

    /** 付款时间 */
    private LocalDateTime payTime;

    /** 发货时间 */
    private LocalDateTime shipTime;

    /** 完成时间 */
    private LocalDateTime completeTime;

    /** 取消原因 / 退款原因 */
    private String cancelReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}