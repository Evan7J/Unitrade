package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.dto.ProductUpdateDTO;
import com.example.unitrade.entity.Category;
import com.example.unitrade.entity.Product;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.CategoryMapper;
import com.example.unitrade.mapper.FavoriteMapper;
import com.example.unitrade.mapper.ProductMapper;
import com.example.unitrade.mapper.UserMapper;
import com.example.unitrade.service.ProductService;
import com.example.unitrade.vo.ProductListVO;
import com.example.unitrade.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品服务实现类
 *
 * 核心流程：
 * 发布商品 → 插入数据库
 * 搜索商品 → 动态构建查询条件 → 分页返回
 * 商品详情 → 查商品 + 查发布者 + 查分类名 + 查是否已收藏
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final FavoriteMapper favoriteMapper;

    /**
     * 发布商品
     *
     * 流程：
     * 1. 从 ThreadLocal 获取当前登录用户 ID
     * 2. DTO 转 Entity，设置默认值
     * 3. 插入数据库
     */
    @Override
    public void publish(ProductPublishDTO dto) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Product product = new Product();
        product.setUserId(userId);
        product.setTitle(dto.getTitle());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setOriginalPrice(dto.getOriginalPrice());
        product.setProductCondition(dto.getProductCondition());
        product.setCategoryId(dto.getCategoryId());
        product.setShippingType(dto.getShippingType() != null ? dto.getShippingType() : 1);
        product.setShippingFee(dto.getShippingFee());
        product.setImages(dto.getImages());
        product.setStatus(1);  // 默认在售
        product.setViewCount(0);

        productMapper.insert(product);
    }

    /**
     * 分页搜索商品
     *
     * 动态构建查询条件：
     * - 关键词 → 模糊匹配标题
     * - 分类ID → 精确匹配
     * - 价格区间 → BETWEEN
     * - 排序 → 按价格或时间
     * - 只查在售商品（status=1）
     *
     * 查完后，把 Product 转成 ProductListVO（精简字段 + 拼接发布者昵称）
     */
    @Override
    public Page<ProductListVO> pageQuery(ProductQueryDTO dto) {
        // 1. 构建查询条件
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // 关键词搜索：匹配商品标题 或 卖家昵称
        if (StringUtils.hasText(dto.getKeyword())) {
            String keyword = dto.getKeyword();
            // 先查匹配昵称的用户ID列表
            List<Long> userIds = userMapper.selectList(
                new LambdaQueryWrapper<User>().like(User::getNickname, keyword)
            ).stream().map(User::getId).collect(Collectors.toList());
            // 标题模糊匹配 或 卖家ID在匹配列表中
            wrapper.and(w -> w
                .like(Product::getTitle, keyword)
                .or().in(!userIds.isEmpty(), Product::getUserId, userIds)
            );
        }

        // 分类筛选
        if (dto.getCategoryId() != null) {
            wrapper.eq(Product::getCategoryId, dto.getCategoryId());
        }

        // 价格区间
        if (dto.getMinPrice() != null) {
            wrapper.ge(Product::getPrice, dto.getMinPrice());
        }
        if (dto.getMaxPrice() != null) {
            wrapper.le(Product::getPrice, dto.getMaxPrice());
        }

        // 按卖家ID筛选（查看某用户的在售商品）
        if (dto.getUserId() != null) {
            wrapper.eq(Product::getUserId, dto.getUserId());
        }

        // 只查在售商品
        wrapper.eq(Product::getStatus, 1);

        // 排序：price_asc 价格升序，price_desc 价格降序，newest/默认按时间降序
        if ("price_asc".equals(dto.getSortBy())) {
            wrapper.orderByAsc(Product::getPrice);
        } else if ("price_desc".equals(dto.getSortBy())) {
            wrapper.orderByDesc(Product::getPrice);
        } else {
            // newest 或空字符串 → 按发布时间降序
            wrapper.orderByDesc(Product::getCreateTime);
        }

        // 2. 分页查询
        Page<Product> page = new Page<>(dto.getPage(), dto.getSize());
        Page<Product> productPage = productMapper.selectPage(page, wrapper);

        // 3. Product 转 ProductListVO（精简字段 + 补充发布者昵称）
        Page<ProductListVO> voPage = new Page<>(dto.getPage(), dto.getSize(), productPage.getTotal());
        List<ProductListVO> voList = productPage.getRecords().stream().map(product -> {
            ProductListVO vo = new ProductListVO();
            vo.setId(product.getId());
            vo.setTitle(product.getTitle());
            vo.setPrice(product.getPrice());
            vo.setOriginalPrice(product.getOriginalPrice());
            vo.setProductCondition(product.getProductCondition());
            vo.setViewCount(product.getViewCount());
            vo.setShippingType(product.getShippingType());
            vo.setShippingFee(product.getShippingFee());
            vo.setCreateTime(product.getCreateTime());

            // 取第一张图片作为封面
            if (StringUtils.hasText(product.getImages())) {
                vo.setCoverImage(product.getImages().split(",")[0]);
            }

            // 查发布者昵称和头像
            User user = userMapper.selectById(product.getUserId());
            vo.setNickname(user != null ? user.getNickname() : "未知用户");
            vo.setAvatarUrl(user != null ? user.getAvatarUrl() : null);

            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 查看商品详情（带 Redis 缓存）
     *
     * @Cacheable 说明：
     *   - cacheNames="product" → Redis 中 key 前缀为 "product::"
     *   - key="#productId" → 按商品ID区分缓存，如 "product::123"
     *   - 首次查询走数据库并写入 Redis，后续查询直接走 Redis 缓存
     *   - 当商品信息更新（编辑/下架）时需手动清除缓存
     *
     * 流程：
     * 1. 查商品基本信息
     * 2. 查发布者信息（昵称、头像）
     * 3. 查分类名称
     * 4. 查当前用户是否已收藏
     * 5. 浏览量 +1
     */
    @Override
    @Cacheable(value = "product", key = "#productId")
    public ProductVO getDetail(Long productId) {
        // 1. 查商品
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }

        // 2. 查发布者
        User seller = userMapper.selectById(product.getUserId());

        // 3. 查分类名称
        Category category = categoryMapper.selectById(product.getCategoryId());

        // 4. 组装返回对象
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setUserId(product.getUserId());
        vo.setNickname(seller != null ? seller.getNickname() : "未知用户");
        vo.setAvatarUrl(seller != null ? seller.getAvatarUrl() : null);
        vo.setCategoryName(category != null ? category.getName() : "未分类");
        vo.setTitle(product.getTitle());
        vo.setDescription(product.getDescription());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setProductCondition(product.getProductCondition());
        vo.setShippingType(product.getShippingType());
        vo.setShippingFee(product.getShippingFee());
        vo.setStatus(product.getStatus());
        vo.setViewCount(product.getViewCount());
        vo.setCreateTime(product.getCreateTime());

        // 图片路径逗号分隔 → 转数组
        if (StringUtils.hasText(product.getImages())) {
            vo.setImages(Arrays.asList(product.getImages().split(",")));
        } else {
            vo.setImages(Collections.emptyList());
        }

        // 5. 判断当前用户是否已收藏
        Long userId = JwtInterceptor.getCurrentUserId();
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<com.example.unitrade.entity.Favorite>()
                        .eq(com.example.unitrade.entity.Favorite::getUserId, userId)
                        .eq(com.example.unitrade.entity.Favorite::getProductId, productId)
        );
        vo.setIsFavorite(count > 0);

        // 6. 浏览量 +1
        product.setViewCount(product.getViewCount() + 1);
        productMapper.updateById(product);

        return vo;
    }

    /**
     * 编辑商品
     * 只能编辑自己的商品，且商品必须在售
     * 编辑后清除该商品的 Redis 缓存，下次查询会重新加载最新数据
     */
    @Override
    @CacheEvict(value = "product", key = "#dto.id")
    public void update(ProductUpdateDTO dto) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Product product = productMapper.selectById(dto.getId());
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (!product.getUserId().equals(userId)) {
            throw new BusinessException("只能编辑自己的商品");
        }

        // 更新字段
        product.setTitle(dto.getTitle());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setOriginalPrice(dto.getOriginalPrice());
        product.setProductCondition(dto.getProductCondition());
        product.setCategoryId(dto.getCategoryId());
        product.setShippingType(dto.getShippingType());
        product.setShippingFee(dto.getShippingFee());
        product.setImages(dto.getImages());

        productMapper.updateById(product);
    }

    /**
     * 下架商品
     * 只能下架自己的商品，把 status 改成 3
     * 下架后清除该商品的 Redis 缓存
     */
    @Override
    @CacheEvict(value = "product", key = "#productId")
    public void offline(Long productId) {
        Long userId = JwtInterceptor.getCurrentUserId();

        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (!product.getUserId().equals(userId)) {
            throw new BusinessException("只能下架自己的商品");
        }

        product.setStatus(3); // 已下架
        productMapper.updateById(product);
    }
}