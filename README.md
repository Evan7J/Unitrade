# UniTrade — 校园闲置交易平台

一个面向校园场景的二手交易平台，后端自己写的，前端是 AI 辅助生成的。

做这个项目主要是想完整走一遍 Spring Boot 开发流程，把常见的后端技术点都串起来。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.4 |
| ORM | MyBatis-Plus 3.5 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis |
| 认证 | JWT（jjwt 0.12） |
| 密码加密 | BCrypt |
| 实时通信 | WebSocket |
| 接口限流 | Guava RateLimiter |
| Java 版本 | 21 |

## 做了什么

相比市面上常见的 CRUD 项目，这个项目额外做了几个东西：

**BCrypt 密码加密** — 用户密码存的不是明文，也不是 MD5，用的是 BCrypt 加盐哈希。这个比较基础，但现在很多教学项目还在用 MD5，面试的时候被问到就尴尬了。

**Guava 接口限流** — 用 RateLimiter 拦截器对每个 IP 做请求限流，读操作 20/s，写操作 3/s。如果项目真的上线，这能防止别人拿脚本刷接口。没上 Redis 方案，因为不想为了一个限流再引入额外依赖，Guava 本地内存就够用了。

**WebSocket 聊天** — 买家和卖家可以在线聊，用的是 Spring 自带的 WebSocket 支持，没有引入第三方推送服务。消息直接存 MySQL，没接消息队列，因为对校园场景来说并发量不需要那么高。

**Redis 缓存** — 商品详情加了缓存，TTL 10 分钟。主要是减少数据库的读压力，首页商品列表频繁查询的地方也用上了。

## 功能模块

**用户端**
- 注册登录（BCrypt 加密）
- 商品浏览、搜索、分类筛选
- 发布商品、编辑、下架
- 商品收藏
- 在线聊天（WebSocket）
- 下单购买
- 订单管理（买家/卖家视角）
- 收货地址管理
- 评价

**管理后台**
- 数据概览（仪表盘）
- 商品管理（审核/下架）
- 订单管理
- 用户管理
- 分类管理
- 公告管理
- Banner 管理

## 快速启动

**前置条件**：JDK 21、MySQL 8.0、Redis、Maven。

```bash
# 克隆
git clone https://github.com/Evan7J/unitrade.git
cd unitrade/Uni-trade

# 建数据库
# 在 MySQL 里执行：CREATE DATABASE campus_trade DEFAULT CHARACTER SET utf8mb4;

# 修改 application.yml 里的数据库密码和 Redis 连接信息

# 启动
mvn spring-boot:run

# 访问 http://localhost:8080
```

## 项目结构

```
src/main/java/com/example/unitrade/
├── common/        # 统一返回、异常处理
├── config/        # JWT拦截器、限流拦截器、WebSocket配置
├── controller/    # 接口层（用户端 + 管理后台）
├── dto/           # 数据传输对象
├── entity/        # 数据库实体
├── mapper/        # MyBatis-Plus Mapper
├── service/       # 业务逻辑层
├── util/          # JWT 工具类
├── vo/            # 视图对象
└── websocket/     # WebSocket 聊天端点
```

## 不足和待改进

- [ ] 前端是 AI 生成的，代码质量一般，但对后端开发岗位来说够用了
- [ ] 没有写单元测试，后面有时间补上
- [ ] 聊天消息目前直接存 MySQL，量大了会慢，可以换成 MongoDB 或者接 MQ 异步落库
- [ ] 图片上传现在是存本地，应该换成 OSS
- [ ] 没做 Docker 部署，还是手动启动的方式
- [ ] 没有 Elasticsearch，搜索目前是 MySQL LIKE，数据多了会慢

## 关于这个项目

这个项目是和大模型配合写的，我负责后端整体架构设计、数据库表结构、核心业务逻辑和代码审查，前端和部分模板代码由 AI 生成后我再调整。

如果面试官看到这个项目，可以直接问下面几个方向的问题，我都准备了对应的面试说辞：

- JWT 的认证流程和安全性
- BCrypt 和 MD5 的区别
- 接口限流的实现方式（Guava 本地 vs Redis 分布式）
- WebSocket 在 Spring 里的配置和连接管理
- MyBatis-Plus 的常用查询方式
