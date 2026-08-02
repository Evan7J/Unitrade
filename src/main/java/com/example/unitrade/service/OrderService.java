package com.example.unitrade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.dto.OrderCreateDTO;
import com.example.unitrade.vo.OrderVO;

/**
 * 订单服务接口
 */
public interface OrderService {

    /**
     * 买家下单（商品锁定，进入待付款状态）
     */
    OrderVO create(OrderCreateDTO dto);

    /**
     * 买家付款
     */
    void pay(Long orderId);

    /**
     * 卖家发货
     */
    void ship(Long orderId);

    /**
     * 买家确认收货，交易完成
     */
    void confirmReceive(Long orderId);

    /**
     * 取消订单
     * 待付款：任何人可取消
     * 已付款：卖家可取消（自动退款）
     */
    void cancel(Long orderId, String reason);

    /**
     * 买家申请退款
     * 仅已付款、已发货状态可申请
     */
    void requestRefund(Long orderId, String reason);

    /**
     * 卖家同意退款
     */
    void agreeRefund(Long orderId);

    /**
     * 卖家拒绝退款
     */
    void rejectRefund(Long orderId);

    /**
     * 我买到的订单（分页）
     * @param page 当前页码
     * @param size 每页条数
     */
    Page<OrderVO> myBuyOrders(int page, int size);

    /**
     * 我卖出的订单（分页）
     * @param page 当前页码
     * @param size 每页条数
     */
    Page<OrderVO> mySellOrders(int page, int size);
}