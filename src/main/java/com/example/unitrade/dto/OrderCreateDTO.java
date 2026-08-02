package com.example.unitrade.dto;

import lombok.Data;

/**
 * 下单请求参数
 *
 * 前端发来的 JSON 示例：
 * { "productId": 1 }
 */
@Data
public class OrderCreateDTO {

    /** 要购买的商品ID */
    private Long productId;
}