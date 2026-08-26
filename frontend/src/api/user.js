// ============================================================
// api/user.js —— 用户相关接口方法
// ============================================================
// 【通俗理解】
// 这个文件就是"接口说明书"，把后端每个接口封装成一个 JS 函数。
// 页面里只需要 import { login } from '../api/user'，然后调用 login(data) 就行。
// 每个函数对应后端 UserController 里的一个方法。
// ============================================================

// 导入我们封装好的 request 工具（就是那个"电话机"）
import request from '../utils/request'

// ============================================================
// 1. 注册接口
// ============================================================
// 【后端对应】
//   UserController.java:
//     @PostMapping("/register")
//     public Result<?> register(@RequestBody RegisterDTO dto)
//
// 【参数说明】
//   d → 注册数据对象 { phone: "xxx", password: "xxx", nickname: "xxx" }
//       这个对象会被 axios 自动转成 JSON 放到请求体里
//       后端 @RequestBody 自动把 JSON 转成 RegisterDTO 对象
//
// 【URL 拼接过程】
//   request.post('/user/register', d)
//   → baseURL '/api' + '/user/register' = '/api/user/register'
//   → Vite 代理转发 → http://localhost:8080/api/user/register
//   → 匹配到 @RequestMapping("/api/user") + @PostMapping("/register")
export const register = (d) => request.post('/user/register', d)

// ============================================================
// 2. 登录接口（重点！）
// ============================================================
// 【后端对应】
//   UserController.java:
//     @PostMapping("/login")                                    ← 请求方式：POST
//     public Result<Map<String, Object>> login(                 ← 返回类型：Result
//         @RequestBody LoginDTO dto                             ← 入参：LoginDTO
//     )
//
// 【参数对齐（重中之重！）】
//   前端传的 JSON：         后端 LoginDTO 接收：
//   {                        public class LoginDTO {
//     "phone": "13800138000",  → private String phone;
//     "password": "123456"     → private String password;
//   }                        }
//
//   ⚠️ 字段名必须一模一样！前端 JSON 的 key 必须和后端 DTO 的字段名一致！
//   前端写 "phone"，后端 LoginDTO 也要有 phone 字段。
//   如果前端写 "mobilePhone"，后端写 phone，就对不上了，后端收不到数据！
//
// 【调用方式】
//   在 Login.vue 里：
//     import { login } from '../api/user'
//     const res = await login({ phone: '13800138000', password: '123456' })
//     // res = { code: 200, msg: "登录成功", data: { token: "eyJ...", role: "admin" } }
export const login = (d) => request.post('/user/login', d)

// ============================================================
// 3. 获取当前用户个人信息
// ============================================================
// 【后端对应】
//   UserController.java:
//     @GetMapping("/profile")                                   ← 请求方式：GET
//     public Result<User> profile()
//
// 【注意】GET 请求没有请求体，不需要传参数
//   request.get('/user/profile')  →  GET /api/user/profile
export const getProfile = () => request.get('/user/profile')

// ============================================================
// 4. 更新用户个人信息
// ============================================================
// 【后端对应】
//   UserController.java:
//     @PutMapping("/profile")                                   ← 请求方式：PUT
//     public Result<?> updateProfile(@RequestBody User user)
export const updateProfile = (d) => request.put('/user/profile', d)

// ============================================================
// 5. 根据用户ID获取用户信息
// ============================================================
// 【后端对应】
//   UserController.java:
//     @GetMapping("/info/{id}")                                 ← 路径参数：{id}
//     public Result<User> getUserInfo(@PathVariable Long id)    ← 从 URL 路径取 id
//
// 【调用示例】
//   getUserInfo(5) →  GET /api/user/info/5
//   后端 @PathVariable 从 URL 里取出 5，赋值给 Long id
export const getUserInfo = (id) => request.get('/user/info/' + id)