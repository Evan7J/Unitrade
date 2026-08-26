// ============================================================
// api/upload.js —— 图片上传接口方法
// ============================================================
// 【通俗理解】
// 图片上传和其他接口不一样的地方：
//   普通接口传的是 JSON 数据（{ "title": "xxx" }）
//   图片上传传的是文件（FormData 格式），需要特殊处理
// ============================================================

import request from '../utils/request'

// ============================================================
// 1. 上传单张图片
// ============================================================
// 【后端对应】
//   UploadController.java:
//     @PostMapping("/image")
//     public Result<Map<String, String>> uploadImage(
//         @RequestParam("file") MultipartFile file
//     )
//
// 【关键区别：FormData 而不是 JSON】
//   普通 POST 请求：
//     request.post('/user/login', { phone: 'xxx', password: 'xxx' })
//     → Content-Type: application/json
//     → 请求体：{"phone":"xxx","password":"xxx"}
//
//   图片上传请求：
//     const fd = new FormData()        ← 创建 FormData 对象
//     fd.append('file', file)          ← 往里面放文件，key 叫 "file"
//     request.post('/upload/image', fd, {
//       headers: { 'Content-Type': 'multipart/form-data' }
//     })
//     → Content-Type: multipart/form-data
//     → 请求体：二进制文件数据（不是 JSON）
//
//   后端 @RequestParam("file") 从 FormData 里取出 key 为 "file" 的文件
//
// 【返回结果】
//   { code: 200, msg: "操作成功", data: { url: "/uploads/abc-123.jpg" } }
//   拿到 url 后拼到发布表单的 images 字段里
//
// 【调用示例】
//   const file = 用户选择的图片文件对象
//   const res = await uploadImage(file)
//   const imageUrl = res.data.url  // → "/uploads/abc-123.jpg"
export const uploadImage = (file) => {
  // 1. 创建 FormData 对象（专门用来装文件的"快递包裹"）
  const fd = new FormData()
  // 2. 把文件放进去，key 叫 "file"（必须和后端 @RequestParam("file") 一致）
  fd.append('file', file)
  // 3. 发 POST 请求，请求头设为 multipart/form-data
  return request.post('/upload/image', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// ============================================================
// 2. 上传多张图片（批量上传）
// ============================================================
// 【后端对应】
//   UploadController.java:
//     @PostMapping("/images")
//     public Result<List<String>> uploadImages(
//         @RequestParam("files") MultipartFile[] files
//     )
//
// 【返回结果】
//   { code: 200, data: ["/uploads/a.jpg", "/uploads/b.jpg"] }
//   data 直接是 URL 数组
export const uploadImages = (files) => {
  const fd = new FormData()
  // 遍历文件数组，每个文件都 append 到 fd 里，key 都叫 "files"
  files.forEach(f => fd.append('files', f))
  return request.post('/upload/images', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}