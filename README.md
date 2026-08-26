# UniTrade - 校园二手交易平台

一个校园二手闲置交易平台，支持发布商品、搜索、买卖家实时聊天、订单管理。

## 技术栈

- 后端：SpringBoot 3.4 + MyBatis-Plus + JWT + WebSocket + Redis
- 前端：Vue3 + Element Plus + TailwindCSS
- 数据库：MySQL 8 + Redis

## 做了啥

商品功能比较常规，发布、搜索、分类筛选、收藏。搜索支持标题和卖家昵称模糊匹配，商品详情加了 Spring Cache + Redis 缓存，编辑或者下架的时候自动清缓存。

聊天这块用 WebSocket 做的，前端连上长连接，消息直接推，不用一直轮询。在线用户用 ConcurrentHashMap 存着，消息先写到数据库再推送，这样就算人不在线，下次登录也能看到聊天记录。

订单流程参考了闲鱼的：待付款 -> 已付款 -> 已发货 -> 已完成，支持取消和退款。下单的时候会检查这个商品有没有正在进行的订单，防止同个商品被多个人同时买走。

接口限流用了 Guava 的 RateLimiter，读接口 20 次/秒、写接口 3 次/秒，按 IP 区分。认证用的 JWT，前端请求头带 token，后端拦截器校验，用户 ID 通过 ThreadLocal 传递，不用每次都从 token 解析。

后台管理分了 7 个模块：数据概览、公告、轮播图、商品分类、商品管理、订单管理、角色管理。

## 怎么跑

确保 MySQL 和 Redis 先跑起来，然后导入项目根目录下的 `init.sql` 建库建表。

```bash
git clone https://github.com/Evan7J/unitrade.git
cd unitrade
# 设置环境变量 DEEPSEEK_API_KEY（DeepSeek 密钥）和 DB_PASSWORD（数据库密码），然后启动
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm install
npm run dev
```

管理员账号：`admin` / `Admin@123456`
测试用户：`13800138000` / `123456`

## 页面截图

发布商品：

![发布商品](screenshots/publish.png)

个人主页：

![个人主页](screenshots/profile.png)

后台 Dashboard：

![后台Dashboard](screenshots/admin-dashboard.png)

分类管理：

![分类管理](screenshots/category-manage.png)

## AI 闲置助手

基于 DeepSeek 大模型实现的自研 Agent，不依赖 Spring AI 框架，直接通过 HTTP 调用 DeepSeek API 并实现工具调用循环（ReAct 模式）。

- 模型：DeepSeek V4 Flash（关闭思考模式，保证工具调用稳定）
- 工具：`searchProducts`（商品搜索，含同义词扩展）、`listCategories`（分类查询）、`draftProduct`（一键生成发布草稿）

### 能力

1. **智能导购**：用户用自然语言描述需求，模型自动调用搜索工具查库，返回可点击的商品卡片（跳转商品详情）。
2. **一键发布**：用户描述商品，模型抽取标题、价格、成色、分类并生成发布草稿，前端回填表单，用户确认后发布。

### 启动方式

1. 配置环境变量 `DEEPSEEK_API_KEY`
2. 启动 SpringBoot 项目
3. 前端 AI 助手页面调用 `POST /api/agent/chat` 接口