package com.example.unitrade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.dto.ProductPublishDTO;
import com.example.unitrade.dto.ProductQueryDTO;
import com.example.unitrade.dto.ProductUpdateDTO;
import com.example.unitrade.vo.ProductListVO;
import com.example.unitrade.vo.ProductVO;

/**
 * 商品服务接口
 */
public interface ProductService {

    /**
     * 发布商品
     */
    void publish(ProductPublishDTO dto);

    /**
     * 分页搜索商品列表
     * @param dto 搜索条件（关键词、分类、价格区间、排序）
     * @return 分页结果
     */
    Page<ProductListVO> pageQuery(ProductQueryDTO dto);

    /**
     * 查看商品详情
     * @param productId 商品ID
     * @return 商品详情（含发布者信息、分类名、是否已收藏）
     */
    ProductVO getDetail(Long productId);

    /**
     * 编辑商品（只能编辑自己的）
     */
    void update(ProductUpdateDTO dto);

    /**
     * 下架商品（只能下架自己的）
     */
    void offline(Long productId);
}