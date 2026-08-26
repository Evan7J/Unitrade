// ============================================================
// api/goods.js —— 商品相关接口方法
// ============================================================
// 【通俗理解】
// 商品列表、商品详情等接口的封装。
// 和 api/user.js 一样，每个函数对应后端 ProductController 的一个方法。
// ============================================================

import request from '../utils/request'

// ============================================================
// 1. 获取商品列表（分页+搜索）
// ============================================================
// 【后端对应】
//   ProductController.java:
//     @GetMapping("/list")
//     public Result<Page<ProductListVO>> list(ProductQueryDTO dto)
//
// 【参数说明】
//   params 是一个对象，比如：
//     { page: 1, size: 8, keyword: '手机', categoryId: 1 }
//
// 【GET 请求传参的特殊写法】
//   request.get('/product/list', { params })
//   注意：第二个参数是 { params: {...} }，不是直接传对象
//   这是 axios 的规定：GET 请求的参数要放在 params 字段里
//   axios 会自动把 params 对象转成 URL 查询字符串：
//     /product/list?page=1&size=8&keyword=手机&categoryId=1
//
// 【返回结果】
//   {
//     code: 200,
//     msg: "操作成功",
//     data: {
//       records: [{ id: 1, title: "iPhone", price: 3500, ... }, ...],  ← 商品数组
//       total: 50,     ← 总条数
//       size: 8,       ← 每页条数
//       current: 1,    ← 当前页码
//       pages: 7       ← 总页数
//     }
//   }
//   页面里取商品数组：res.data.records
//   页面里取总条数：  res.data.total
export const getGoodsList = (params = {}) => {
  return request.get('/product/list', { params })
}

// ============================================================
// 2. 获取商品详情
// ============================================================
// 【后端对应】
//   ProductController.java:
//     @GetMapping("/detail/{id}")
//     public Result<ProductVO> detail(@PathVariable Long id)
//
// 【调用示例】
//   getGoodsDetail(5) → GET /api/product/detail/5
export const getGoodsDetail = (id) => {
  return request.get(`/product/detail/${id}`)
}