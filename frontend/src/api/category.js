// ============================================================
// api/category.js —— 分类相关接口方法
// ============================================================

import request from '../utils/request'

// ============================================================
// 获取所有分类
// ============================================================
// 【后端对应】
//   CategoryController.java:
//     @GetMapping("/list")
//     public Result<List<Category>> list()
//
// 【调用示例】
//   const res = await getList()
//   // res.data = [{ id: 1, name: "数码产品", sortOrder: 1 }, ...]
//
// 【注意】这是 GET 请求，没有参数
export const getList = () => request.get('/category/list')