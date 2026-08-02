package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Order;
import com.example.unitrade.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderMapper orderMapper;

    @GetMapping("/list")
    public Result<Page<Order>> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(Order::getStatus, status);
        wrapper.orderByDesc(Order::getCreateTime);
        return Result.success(orderMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { orderMapper.deleteById(id); return Result.success("删除成功"); }
}