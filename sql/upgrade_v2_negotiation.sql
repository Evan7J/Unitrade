-- ============================================================================
-- UniTrade 升级 · 议价 Agent 数据表（阶段 2）
-- 生成日期：2026-09-23
--
-- 执行方式：
--   mysql -h 127.0.0.1 -u root -p "campus_trade" -e "source sql/upgrade_v2_negotiation.sql"
--
-- 设计原则：全部 CREATE TABLE IF NOT EXISTS，重复执行安全；
--          不修改任何既有表。
-- ============================================================================

USE campus_trade;

-- ---------------------------------------------------------------------------
-- 1. 商品议价授权区间 —— 卖家底价在数据库里的唯一落点
--
-- 为什么单独开一张表而不是给 t_product 加字段：
--   t_product 是"公开商品事实"，会被商品列表、详情、搜索大量读取，
--   底价一旦混进去，任何一次 select * 都可能把它带出去。
--   单独一张表 + 只有议价链路才查它 = 把敏感数据的读取面缩到最小。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_authorization (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id    BIGINT        NOT NULL COMMENT '商品ID',
    seller_id     BIGINT        NOT NULL COMMENT '卖家ID',
    floor_price   DECIMAL(10,2) NOT NULL COMMENT '卖家底价（授权下限，绝不对外）',
    strategy_name VARCHAR(64)   NOT NULL DEFAULT 'composed' COMMENT '定价策略名',
    version       INT           NOT NULL DEFAULT 1 COMMENT '授权版本号；卖家改价时 +1，只对新会话生效',
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_product (product_id),
    KEY idx_seller (seller_id)
) COMMENT = '商品议价授权区间（底价）';

-- ---------------------------------------------------------------------------
-- 2. 议价会话
--
-- 关键设计：把授权区间「快照」进会话。
--   卖家中途改底价时，进行中的会话必须继续用创建时的快照 ——
--   否则同一场谈判里底价会变，议价记录不可追溯，事后无法复盘。
--   auth_version 用于标识这个快照对应哪一版授权。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_negotiation_session (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no              VARCHAR(32)   NOT NULL COMMENT '会话编号（对外暴露）',
    product_id              BIGINT        NOT NULL,
    buyer_id                BIGINT        NOT NULL,
    seller_id               BIGINT        NOT NULL,
    auth_version            INT           NOT NULL COMMENT '会话创建时的授权版本（快照标识）',
    snapshot_list_price     DECIMAL(10,2) NOT NULL COMMENT '快照：挂牌价',
    snapshot_floor_price    DECIMAL(10,2) NOT NULL COMMENT '快照：底价（服务端专用，不进任何 VO）',
    snapshot_days_listed    INT           NOT NULL COMMENT '快照：上架天数',
    phase                   VARCHAR(24)   NOT NULL DEFAULT 'BARGAINING'
                            COMMENT '会话状态：BARGAINING 议价中 / AGREED 已达成 / SUSPENDED 挂起转人工 / STALLED 僵局 / CLOSED 已关闭',
    round_no                INT           NOT NULL DEFAULT 0 COMMENT '当前轮次（单调递增）',
    current_quote           DECIMAL(10,2) NULL COMMENT '我方当前报价',
    last_buyer_offer        DECIMAL(10,2) NULL COMMENT '买家最近一次出价',
    agreed_price            DECIMAL(10,2) NULL COMMENT '成交价（达成时写入）',
    order_id                BIGINT        NULL COMMENT '成交后生成的订单ID',
    suspend_reason          VARCHAR(48)   NULL COMMENT '挂起原因',
    create_time             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_product_buyer (product_id, buyer_id),
    KEY idx_buyer (buyer_id),
    KEY idx_seller (seller_id),
    KEY idx_phase (phase)
) COMMENT = '议价会话';

-- ---------------------------------------------------------------------------
-- 3. 议价轮次明细
--
-- ★ 四重防重中的「数据库唯一索引」这一重就落在这里：
--   uk_session_round —— 同一会话同一轮次只能有一条，重复出价在第 4 重被拦；
--   uk_message       —— 同一 messageId 只能有一条，配合 Redis SET NX 做前后双保险。
--   message_id 允许为 NULL（唯一索引对多个 NULL 不生效），兼容"没有消息ID"的调用路径。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_negotiation_round (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT        NOT NULL,
    round_no      INT           NOT NULL COMMENT '轮次序号（从 1 开始，必须单调 +1）',
    intent        VARCHAR(24)   NOT NULL COMMENT '意图：ASK_PRICE/OFFER/PROBE_FLOOR/PRESSURE/CONDITION/CHITCHAT/COMPLAINT/CONFIRM/UNKNOWN',
    buyer_offer   DECIMAL(10,2) NULL COMMENT '买家本轮出价',
    counter_quote DECIMAL(10,2) NULL COMMENT '我方本轮还价',
    branch        VARCHAR(24)   NOT NULL COMMENT '分支：ACCEPTED/COUNTERED/SUSPENDED/ESCALATED/REFUSED/STALLED',
    message_id    VARCHAR(64)   NULL COMMENT '客户端消息ID（幂等去重键）',
    agent_reply   TEXT          NULL COMMENT 'AI 生成的话术',
    token_cost    DECIMAL(12,6) NULL COMMENT '本轮 LLM 成本（元），用于成本核算',
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_round (session_id, round_no),
    UNIQUE KEY uk_message (message_id),
    KEY idx_session (session_id)
) COMMENT = '议价轮次明细';

-- ---------------------------------------------------------------------------
-- 4. 风控留痕
--
-- 「回放 200 条对抗性议价会话，越界出价拦截 13 次、误放 0 次」这个指标的数据来源。
-- 每条拦截记一行，包含原因与出价 —— 面试官追问"举一个越界例子"时能直接查出来。
--
-- ⚠️ 口径：本表只记【价格类】风控事件。两类由意图触发的挂起【不写这里】：
--    · INTENT_COMPLAINT（买家情绪投诉）—— 那是体验问题，不是价格问题；
--    · REFUSED（套底价被拒）—— 会话不挂起，且没有出价越界。
--    它们由 t_negotiation_round 的 intent + branch 承担可追溯性。
--    原因：一个数字只有在口径唯一时才可复现 —— 把非价格事件混进来，
--    "拦截 N 次"就失去了唯一定义。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_bargain_guard_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT        NULL,
    product_id    BIGINT        NOT NULL,
    buyer_id      BIGINT        NOT NULL,
    reason        VARCHAR(48)   NOT NULL COMMENT '价格类拦截原因：OFFER_BELOW_FLOOR/QUOTE_BELOW_FLOOR/QUOTE_NOT_MONOTONIC/ROUND_LIMIT_EXCEEDED/HUMAN_RELEASED_BELOW_FLOOR',
    offered_price DECIMAL(10,2) NULL COMMENT '触发拦截的出价',
    counter_quote DECIMAL(10,2) NULL COMMENT '拦下时的我方报价',
    detail        VARCHAR(255)  NULL COMMENT '补充说明（不含底价）',
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    KEY idx_product (product_id),
    KEY idx_reason (reason),
    KEY idx_create_time (create_time)
) COMMENT = '议价风控留痕';

-- ---------------------------------------------------------------------------
-- 5. 幂等去重（四重防重的第 1 重，Redis 之外的持久化兜底）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_negotiation_idempotency (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id     VARCHAR(64)  NOT NULL COMMENT '消息ID',
    session_id     BIGINT       NULL,
    result_summary VARCHAR(255) NULL COMMENT '首次处理的结果摘要（便于重复请求直接返回）',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message (message_id)
) COMMENT = '议价幂等去重';

-- ---------------------------------------------------------------------------
-- 自检
-- ---------------------------------------------------------------------------
SELECT '--- 议价相关表 ---' AS section;
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'campus_trade' AND TABLE_NAME LIKE 't_negotiation%' OR TABLE_NAME IN ('t_product_authorization','t_bargain_guard_log')
ORDER BY TABLE_NAME;
