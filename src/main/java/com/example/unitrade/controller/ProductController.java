package com.example.unitrade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.dto.ProductUpdateDTO;
import com.example.unitrade.service.ProductService;
import com.example.unitrade.vo.ProductListVO;
import com.example.unitrade.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 商品接口控制器
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 发布商品
     * POST /api/product/publish
     */
    @PostMapping("/publish")
    public Result<?> publish(@RequestBody ProductPublishDTO dto) {
        productService.publish(dto);
        return Result.success("发布成功");
    }

    /**
     * 商品列表（分页搜索）
     * GET /api/product/list?keyword=手机&categoryId=1&page=1&size=10
     *
     * 注意：这里不用 @RequestBody，因为 GET 请求参数在 URL 上
     * Spring 会自动把 URL 参数绑定到 ProductQueryDTO 的字段上
     */
    @GetMapping("/list")
    public Result<Page<ProductListVO>> list(ProductQueryDTO dto) {
        return Result.success(productService.pageQuery(dto));
    }

    /**
     * 商品详情
     * GET /api/product/detail/1
     */
    @GetMapping("/detail/{id}")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getDetail(id));
    }

    /**
     * 编辑商品
     * PUT /api/product/update
     */
    @PutMapping("/update")
    public Result<?> update(@RequestBody ProductUpdateDTO dto) {
        productService.update(dto);
        return Result.success("编辑成功");
    }

    /**
     * 下架商品
     * PUT /api/product/offline/{id}
     */
    @PutMapping("/offline/{id}")
    public Result<?> offline(@PathVariable Long id) {
        productService.offline(id);
        return Result.success("下架成功");
    }
}