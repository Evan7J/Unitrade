package com.example.unitrade.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品搜索条件
 *
 * 前端请求示例：
 * GET /api/product/list?keyword=手机&categoryId=1&minPrice=1000&maxPrice=5000&sortBy=price_asc&page=1&size=10
 */
@Data
public class ProductQueryDTO {

    /** 关键词搜索（模糊匹配标题） */
    private String keyword;

    /** 分类ID筛选 */
    private Long categoryId;

    /** 最低价格 */
    private BigDecimal minPrice;

    /** 最高价格 */
    private BigDecimal maxPrice;

    /**
     * 排序方式：
     * price_asc  → 价格从低到高
     * price_desc → 价格从高到低
     * newest     → 最新发布（默认）
     */
    private String sortBy;

    /** 页码，从1开始 */
    private Integer page = 1;

    /** 卖家ID筛选（用于查看某用户的在售商品） */
    private Long userId;

    /** 每页条数 */
    private Integer size = 10;
}