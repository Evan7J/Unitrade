package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Category;
import com.example.unitrade.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口控制器
 *
 * 路由：GET /api/category/list
 * 注意：这个接口没有加 excludePathPatterns，所以需要登录后才能访问
 * 如果你想让未登录也能看分类，去 WebConfig 里加上 "/api/category/list"
 */
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 获取所有分类
     *
     * 前端请求：GET /api/category/list
     * 返回示例：
     * {
     *   "code": 200,
     *   "msg": "操作成功",
     *   "data": [
     *     { "id": 1, "name": "数码产品", "sortOrder": 1 },
     *     { "id": 2, "name": "书籍教材", "sortOrder": 2 },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/list")
    public Result<List<Category>> list() {
        return Result.success(categoryService.listAll());
    }
}