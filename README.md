# UniTrade — 校园闲置交易平台

一个面向校园场景的二手交易平台，Spring Boot + MyBatis-Plus + Redis + WebSocket。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.4 + MyBatis-Plus 3.5 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis |
| 认证 | JWT + BCrypt |
| 实时通信 | WebSocket |
| 接口限流 | Guava RateLimiter |
| Java | 21 |

## 功能

- 用户注册登录（BCrypt 加密）
- 商品发布、浏览、搜索、分类筛选
- 商品收藏、在线聊天（WebSocket）
- 下单购买、订单管理
- 收货地址管理、评价
- 管理后台：商品审核、订单管理、用户管理、数据概览

## 快速启动

需要 JDK 21、MySQL 8.0、Redis、Maven。

```bash
git clone https://github.com/Evan7J/unitrade.git
cd unitrade/Uni-trade
# 在 MySQL 中创建数据库: CREATE DATABASE campus_trade DEFAULT CHARACTER SET utf8mb4;
# 修改 application.yml 中的数据库密码和 Redis 连接信息
mvn spring-boot:run
# 访问 http://localhost:8080
```

## 项目结构

```
src/main/java/com/example/unitrade/
├── common/        # 统一返回、异常处理
├── config/        # JWT 拦截器、限流拦截器、WebSocket 配置
├── controller/    # 接口层（用户端 + 管理后台）
├── dto/           # 数据传输对象
├── entity/        # 数据库实体
├── mapper/        # MyBatis-Plus Mapper
├── service/       # 业务逻辑
├── util/          # JWT 工具类
├── vo/            # 视图对象
└── websocket/     # WebSocket 聊天
```
