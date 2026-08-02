package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Address;
import com.example.unitrade.mapper.AddressMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/address")
@RequiredArgsConstructor
public class AdminAddressController {

    private final AddressMapper addressMapper;

    @GetMapping("/list")
    public Result<Page<Address>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) wrapper.like(Address::getReceiverName, keyword);
        wrapper.orderByDesc(Address::getCreateTime);
        return Result.success(addressMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { addressMapper.deleteById(id); return Result.success("删除成功"); }
}