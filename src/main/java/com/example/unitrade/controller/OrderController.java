package com.example.unitrade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.dto.OrderCreateDTO;
import com.example.unitrade.service.OrderService;
import com.example.unitrade.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 订单接口控制器
 *
 * 完整交易流程：
 *   下单 → 付款 → 发货 → 收货 → 完成
 *   可取消、可退款、可拒绝退款
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 买家下单
     * POST /api/order/create
     * Body: { "productId": 1 }
     */
    @PostMapping("/create")
    public Result<OrderVO> create(@RequestBody OrderCreateDTO dto) {
        return Result.success("下单成功，请尽快付款", orderService.create(dto));
    }

    /**
     * 买家付款
     * PUT /api/order/pay/1
     */
    @PutMapping("/pay/{id}")
    public Result<?> pay(@PathVariable Long id) {
        orderService.pay(id);
        return Result.success("付款成功");
    }

    /**
     * 卖家发货
     * PUT /api/order/ship/1
     */
    @PutMapping("/ship/{id}")
    public Result<?> ship(@PathVariable Long id) {
        orderService.ship(id);
        return Result.success("发货成功");
    }

    /**
     * 买家确认收货
     * PUT /api/order/confirm/1
     */
    @PutMapping("/confirm/{id}")
    public Result<?> confirmReceive(@PathVariable Long id) {
        orderService.confirmReceive(id);
        return Result.success("确认收货成功，交易完成");
    }

    /**
     * 取消订单
     * PUT /api/order/cancel/1
     * Body: { "reason": "不想要了" }
     */
    @PutMapping("/cancel/{id}")
    public Result<?> cancel(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        orderService.cancel(id, reason);
        return Result.success("订单已取消");
    }

    /**
     * 买家申请退款
     * PUT /api/order/refund/1
     * Body: { "reason": "商品与描述不符" }
     */
    @PutMapping("/refund/{id}")
    public Result<?> requestRefund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        orderService.requestRefund(id, reason);
        return Result.success("退款申请已提交");
    }

    /**
     * 卖家同意退款
     * PUT /api/order/agree-refund/1
     */
    @PutMapping("/agree-refund/{id}")
    public Result<?> agreeRefund(@PathVariable Long id) {
        orderService.agreeRefund(id);
        return Result.success("已同意退款");
    }

    /**
     * 卖家拒绝退款
     * PUT /api/order/reject-refund/1
     */
    @PutMapping("/reject-refund/{id}")
    public Result<?> rejectRefund(@PathVariable Long id) {
        orderService.rejectRefund(id);
        return Result.success("已拒绝退款");
    }

    /**
     * 我买到的订单（分页）
     * GET /api/order/buy?page=1&size=10
     */
    @GetMapping("/buy")
    public Result<Page<OrderVO>> myBuyOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(orderService.myBuyOrders(page, size));
    }

    /**
     * 我卖出的订单（分页）
     * GET /api/order/sell?page=1&size=10
     */
    @GetMapping("/sell")
    public Result<Page<OrderVO>> mySellOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(orderService.mySellOrders(page, size));
    }
}