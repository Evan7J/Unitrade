// ============================================================
// api/product.js —— 商品相关接口方法（完整版）
// ============================================================
// 【通俗理解】
// 和 api/user.js 一样，每个函数对应后端 ProductController 的一个方法。
// 这里包含了列表、详情、发布、编辑、下架等所有商品操作。
// ============================================================

import request from '../utils/request'

// 1. 商品列表（分页搜索）→ GET /api/product/list?keyword=&page=1&size=10
export const getList = (p) => request.get('/product/list', { params: p })

// 2. 商品详情 → GET /api/product/detail/5
export const getDetail = (id) => request.get('/product/detail/' + id)

// ============================================================
// 3. 发布商品（重点！）
// ============================================================
// 【后端对应】
//   ProductController.java:
//     @PostMapping("/publish")
//     public Result<?> publish(@RequestBody ProductPublishDTO dto)
//
// 【参数对齐】
//   前端传的 JSON 字段必须和后端 ProductPublishDTO 字段名一致！
//
//   前端 JSON：                    后端 ProductPublishDTO：
//   {                               public class ProductPublishDTO {
//     "title": "iPhone 15",    →      private String title;
//     "description": "...",    →      private String description;
//     "price": 3999.00,        →      private BigDecimal price;
//     "originalPrice": 5999,   →      private BigDecimal originalPrice;
//     "productCondition": 3,   →      private Integer productCondition;
//     "categoryId": 1,         →      private Long categoryId;
//     "shippingType": 2,       →      private Integer shippingType;
//     "shippingFee": 10.00,    →      private BigDecimal shippingFee;
//     "images": "/uploads/..." →      private String images;
//   }                             }
//
// 【调用示例】
//   await publish({
//     title: 'iPhone 15',
//     price: 3999.00,
//     categoryId: 1,
//     images: '/uploads/abc.jpg,/uploads/def.jpg',
//     ...
//   })
//   → POST /api/product/publish
//   → 后端返回 { code: 200, msg: "发布成功" }
export const publish = (d) => request.post('/product/publish', d)

// 4. 编辑商品 → PUT /api/product/update
export const update = (d) => request.put('/product/update', d)

// 5. 下架商品 → PUT /api/product/offline/5
export const offline = (id) => request.put('/product/offline/' + id)