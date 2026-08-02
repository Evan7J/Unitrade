package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.entity.Favorite;
import com.example.unitrade.entity.Product;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.FavoriteMapper;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.mapper.UserMapper;
import com.example.unitrade.service.FavoriteService;
import com.example.unitrade.vo.ProductListVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 收藏服务实现类
 *
 * 收藏去重方案：
 * 数据库 t_favorite 表有 UNIQUE KEY(user_id, product_id)
 * 同一用户重复收藏同一商品会抛异常，Service 层 catch 住后返回友好提示
 * 这比"先查再插"更可靠（高并发下查和插之间有间隙，可能插入重复数据）
 */
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;

    /**
     * 收藏商品
     *
     * 流程：
     * 1. 校验商品是否存在且在售
     * 2. 插入收藏记录
     * 3. 如果已收藏（唯一键冲突），捕获异常返回提示
     */
    @Override
    public void add(Long productId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        // 校验商品是否存在
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() != 1) {
            throw new BusinessException("商品不存在或已下架");
        }

        // 不能收藏自己的商品
        if (product.getUserId().equals(userId)) {
            throw new BusinessException("不能收藏自己的商品");
        }

        // 插入收藏，已收藏会抛唯一键冲突异常
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setProductId(productId);

        try {
            favoriteMapper.insert(favorite);
        } catch (Exception e) {
            throw new BusinessException("已收藏过该商品");
        }
    }

    /**
     * 取消收藏
     */
    @Override
    public void remove(Long productId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        int rows = favoriteMapper.delete(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getProductId, productId)
        );

        if (rows == 0) {
            throw new BusinessException("未收藏该商品");
        }
    }

    /**
     * 我的收藏列表
     *
     * 流程：
     * 1. 查我的收藏记录
     * 2. 根据 productId 批量查商品信息
     * 3. 组装返回
     */
    @Override
    public List<ProductListVO> myFavorites() {
        Long userId = JwtInterceptor.getCurrentUserId();

        // 查收藏记录
        List<Favorite> favorites = favoriteMapper.selectList(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .orderByDesc(Favorite::getCreateTime)
        );

        // 转成 ProductListVO
        return favorites.stream().map(fav -> {
            Product product = productMapper.selectById(fav.getProductId());
            if (product == null) {
                return null;
            }

            ProductListVO vo = new ProductListVO();
            vo.setId(product.getId());
            vo.setTitle(product.getTitle());
            vo.setPrice(product.getPrice());
            vo.setOriginalPrice(product.getOriginalPrice());
            vo.setProductCondition(product.getProductCondition());
            vo.setViewCount(product.getViewCount());
            vo.setCreateTime(product.getCreateTime());

            if (StringUtils.hasText(product.getImages())) {
                vo.setCoverImage(product.getImages().split(",")[0]);
            }

            User user = userMapper.selectById(product.getUserId());
            vo.setNickname(user != null ? user.getNickname() : "未知用户");

            return vo;
        }).collect(Collectors.toList());
    }
}