package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.service.FavoriteService;
import com.example.unitrade.vo.ProductListVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 收藏接口控制器
 */
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 收藏商品
     * POST /api/favorite/add/1
     */
    @PostMapping("/add/{productId}")
    public Result<?> add(@PathVariable Long productId) {
        favoriteService.add(productId);
        return Result.success("收藏成功");
    }

    /**
     * 取消收藏
     * DELETE /api/favorite/remove/1
     */
    @DeleteMapping("/remove/{productId}")
    public Result<?> remove(@PathVariable Long productId) {
        favoriteService.remove(productId);
        return Result.success("取消收藏成功");
    }

    /**
     * 我的收藏列表
     * GET /api/favorite/my
     */
    @GetMapping("/my")
    public Result<List<ProductListVO>> myFavorites() {
        return Result.success(favoriteService.myFavorites());
    }
}
