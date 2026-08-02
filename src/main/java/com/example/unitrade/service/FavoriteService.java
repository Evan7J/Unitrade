package com.example.unitrade.service;

import com.example.unitrade.vo.ProductListVO;

import java.util.List;

/**
 * 收藏服务接口
 */
public interface FavoriteService {

    /**
     * 收藏商品
     * 如果已收藏，重复收藏会触发数据库唯一键冲突，catch 后返回提示
     */
    void add(Long productId);

    /**
     * 取消收藏
     */
    void remove(Long productId);

    /**
     * 我的收藏列表
     */
    List<ProductListVO> myFavorites();
}