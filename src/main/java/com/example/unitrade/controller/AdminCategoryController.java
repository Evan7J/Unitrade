package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Category;
import com.example.unitrade.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/category")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryMapper categoryMapper;

    @PostMapping("/save")
    public Result<?> save(@RequestBody Category c) { categoryMapper.insert(c); return Result.success("添加成功"); }

    @PutMapping("/update")
    public Result<?> update(@RequestBody Category c) { categoryMapper.updateById(c); return Result.success("更新成功"); }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { categoryMapper.deleteById(id); return Result.success("删除成功"); }
}