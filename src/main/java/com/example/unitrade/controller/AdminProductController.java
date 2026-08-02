package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Product;
import com.example.unitrade.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/product")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductMapper productMapper;

    @GetMapping("/list")
    public Result<Page<Product>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) wrapper.like(Product::getTitle, keyword);
        wrapper.orderByDesc(Product::getCreateTime);
        return Result.success(productMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { productMapper.deleteById(id); return Result.success("删除成功"); }

    @PutMapping("/offline/{id}")
    public Result<?> offline(@PathVariable Long id) {
        Product p = productMapper.selectById(id);
        if (p != null) { p.setStatus(3); productMapper.updateById(p); }
        return Result.success("已下架");
    }

    @PutMapping("/online/{id}")
    public Result<?> online(@PathVariable Long id) {
        Product p = productMapper.selectById(id);
        if (p != null) { p.setStatus(1); productMapper.updateById(p); }
        return Result.success("已上架");
    }
}