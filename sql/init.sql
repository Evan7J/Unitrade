-- ============================================================================
-- UniTrade 基础表结构（MySQL 8）
-- 数据库：campus_trade
--
-- 执行方式：
--   mysql -h 127.0.0.1 -u root -p < sql/init.sql
--
-- 范围说明：
--   本脚本只建「业务基础表」。后续两个能力是升级加出来的，各自独立建表，
--   不跑它们的脚本，商品 / 订单 / 聊天这些基础功能照常可用：
--     · AI 助手会话     → sql/init_agent_message.sql
--     · 议价 Agent      → sql/upgrade_v2_negotiation.sql（+ upgrade_v2_cost / _p0_fixes）
--
-- 设计：全部 CREATE TABLE IF NOT EXISTS，可重复执行；种子数据用 INSERT IGNORE。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS campus_trade
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE campus_trade;

-- ---------------------------------------------------------------------------
-- 用户
--   role：user 普通用户（默认）/ admin 管理员（只进后台，不参与前台交易）
--   密码存 BCrypt 哈希，不存明文
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone      VARCHAR(32)  NOT NULL COMMENT '登录账号（手机号；管理员为 admin）',
    password   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname   VARCHAR(50)           COMMENT '昵称',
    avatar_url VARCHAR(500)          COMMENT '头像地址',
    school     VARCHAR(100)          COMMENT '学校',
    bio        VARCHAR(255)          COMMENT '个人简介',
    role       VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '角色：user / admin',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

-- ---------------------------------------------------------------------------
-- 商品分类
--   这 5 个分类同时写死在 QueryParamExtractor.CATEGORIES 里，
--   用于约束大模型做需求解析时的输出空间（不许它自己造分类名）。
--   改这里要同步改那边，否则搜索会解析出匹配不上的分类。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50) NOT NULL COMMENT '分类名称',
    sort_order INT         NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类';

-- ---------------------------------------------------------------------------
-- 商品
--   status：1 在售 / 2 已售出（下单即锁定，防一物多卖）/ 3 已下架
--   product_condition 成色：1 全新 / 2 几乎全新 / 3 轻微使用 / 4 明显使用
--   shipping_type：1 面交 / 2 付邮邮寄 / 3 包邮
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT        NOT NULL COMMENT '发布者（卖家）ID',
    category_id       BIGINT                 COMMENT '分类ID',
    title             VARCHAR(200)  NOT NULL COMMENT '标题',
    description       TEXT                   COMMENT '描述',
    price             DECIMAL(10,2) NOT NULL COMMENT '售价',
    original_price    DECIMAL(10,2)          COMMENT '原价',
    product_condition INT                    COMMENT '成色：1全新 2几乎全新 3轻微使用 4明显使用',
    images            VARCHAR(2000)          COMMENT '图片路径，多张用逗号分隔',
    shipping_type     INT                    COMMENT '物流方式：1面交 2付邮邮寄 3包邮',
    shipping_fee      DECIMAL(10,2)          COMMENT '邮费（shipping_type=2 时有值）',
    status            INT           NOT NULL DEFAULT 1 COMMENT '状态：1在售 2已售出 3已下架',
    view_count        INT           NOT NULL DEFAULT 0 COMMENT '浏览次数',
    create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user (user_id),
    KEY idx_category (category_id),
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品';

-- ---------------------------------------------------------------------------
-- 订单
--   状态：1待付款 2已付款 3已发货 4已完成 5已取消 6退款中 7已退款
--
--   origin_price / deal_price 都是「下单当时的快照」：
--   订单是已发生的事实，不能因为卖家事后改价而跟着变。
--   deal_price 与 origin_price 的差额就是议价让出去的钱 ——
--   没有这个字段，议价谈成的价格无处落库。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    buyer_id             BIGINT        NOT NULL COMMENT '买家ID',
    seller_id            BIGINT        NOT NULL COMMENT '卖家ID',
    product_id           BIGINT        NOT NULL COMMENT '商品ID',
    origin_price         DECIMAL(10,2) NOT NULL COMMENT '下单时的挂牌价（快照）',
    deal_price           DECIMAL(10,2)          COMMENT '实际成交价；未议价时等于挂牌价',
    status               INT           NOT NULL DEFAULT 1 COMMENT '1待付款 2已付款 3已发货 4已完成 5已取消 6退款中 7已退款',
    status_before_refund INT                    COMMENT '申请退款前的状态，用于拒绝退款时正确回退',
    pay_time             DATETIME               COMMENT '付款时间',
    ship_time            DATETIME               COMMENT '发货时间',
    complete_time        DATETIME               COMMENT '完成时间',
    cancel_reason        VARCHAR(255)           COMMENT '取消原因 / 退款原因',
    create_time          DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_buyer (buyer_id),
    KEY idx_seller (seller_id),
    KEY idx_product (product_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';

-- ---------------------------------------------------------------------------
-- 收藏
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_favorite (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL COMMENT '收藏用户ID',
    product_id  BIGINT NOT NULL COMMENT '商品ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品收藏';

-- ---------------------------------------------------------------------------
-- 收货地址
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_address (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL COMMENT '用户ID',
    receiver_name VARCHAR(50)           COMMENT '收货人姓名',
    province      VARCHAR(50)           COMMENT '省份',
    city          VARCHAR(50)           COMMENT '城市',
    district      VARCHAR(50)           COMMENT '区/县',
    detail        VARCHAR(200)          COMMENT '详细地址',
    phone         VARCHAR(20)           COMMENT '联系电话',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址';

-- ---------------------------------------------------------------------------
-- 聊天消息
--   消息先落库再通过 WebSocket 推送：对方不在线，下次登录仍能看到历史记录。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat_message (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id    BIGINT      NOT NULL COMMENT '发送者ID',
    receiver_id  BIGINT      NOT NULL COMMENT '接收者ID',
    product_id   BIGINT               COMMENT '关联商品ID（从商品详情页发起聊天时有值）',
    content      TEXT                 COMMENT '消息内容',
    message_type VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT '消息类型：text / image',
    is_read      INT         NOT NULL DEFAULT 0 COMMENT '是否已读：0未读 1已读',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_receiver_read (receiver_id, is_read),
    KEY idx_pair (sender_id, receiver_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息';

-- ---------------------------------------------------------------------------
-- 订单评价
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_review (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT NOT NULL COMMENT '订单ID',
    reviewer_id BIGINT NOT NULL COMMENT '评价人ID',
    reviewee_id BIGINT NOT NULL COMMENT '被评价人ID',
    product_id  BIGINT          COMMENT '商品ID',
    rating      INT             COMMENT '评分 1-5',
    content     VARCHAR(500)    COMMENT '评价内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_reviewer (order_id, reviewer_id),
    KEY idx_reviewee (reviewee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单评价';

-- ---------------------------------------------------------------------------
-- 公告
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_announcement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL COMMENT '公告标题',
    content     TEXT                  COMMENT '公告内容（富文本 HTML）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公告';

-- ---------------------------------------------------------------------------
-- 首页轮播图
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_banner (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    image_url       VARCHAR(500) COMMENT '图片URL',
    object_position VARCHAR(50)  COMMENT '图片裁剪位置，如 "center top"、"50% 30%"',
    sort_order      INT NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '首页轮播图';

-- ============================================================================
-- 种子数据
-- ============================================================================

-- 分类：必须与 QueryParamExtractor.CATEGORIES 一致
INSERT IGNORE INTO t_category (id, name, sort_order) VALUES
    (1, '数码产品', 1),
    (2, '书籍教材', 2),
    (3, '服饰鞋包', 3),
    (4, '运动户外', 4),
    (5, '其他', 5);

-- 账号
--   admin      / Admin@123456  后台管理员
--   13800138000 / 123456       普通用户
-- 下面两串是上面两个明文口令的 BCrypt 哈希（已实测可登录）。
-- 想换密码：用 BCryptPasswordEncoder().encode("新密码") 生成，或走注册接口。
INSERT IGNORE INTO t_user (phone, password, nickname, role) VALUES
    ('admin', '$2a$10$WlKcABS6SmauEysaczqnM.dEIbH9fNVoxpQSSRGmX5S91nWW69GU.', '管理员', 'admin'),
    ('13800138000', '$2a$10$yMPF3L6vdzjZeUppQCtg6uidNpLPXxHMJTJZkzJVjCHsxUrjTbgF6', '测试用户', 'user');