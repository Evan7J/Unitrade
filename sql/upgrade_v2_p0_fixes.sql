-- ============================================================================
-- UniTrade 升级 · 阶段 1 数据库变更（P0-B / P0-C / P0-E）
-- 生成日期：2026-09-23
--
-- 执行方式：
--   mysql -h 127.0.0.1 -u root -p "campus_trade" -e "source sql/upgrade_v2_p0_fixes.sql"
--
-- 设计原则（三条，都是为了"出事能退回去"）：
--   1. 只新增列，不删除、不修改现有列
--   2. 幂等：可重复执行（用 information_schema 判断列是否已存在）
--   3. 数据订正前先备份到 _bak_ 表，便于核对与回滚
-- ============================================================================

USE campus_trade;

-- ---------------------------------------------------------------------------
-- 0. 幂等辅助：列不存在才添加
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 的特性），
--    所以用 information_schema 判断后动态执行。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS _unitrade_add_column;
DELIMITER //
CREATE PROCEDURE _unitrade_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- ---------------------------------------------------------------------------
-- 1. P0-C：订单价格快照
--
-- 为什么必须存快照：原来 OrderVO.productPrice 是实时查 product.price，
-- 卖家一改价，历史订单显示的金额就跟着变 —— 账对不上，也无法追溯真实成交价。
-- 议价功能更是必须把"谈成的价"落在订单上，否则无处可放。
--
-- origin_price = 下单时的挂牌价（快照，用于展示"原价/划掉价"）
-- deal_price   = 实际成交价（议价达成时为议价价；未议价时等于 origin_price）
-- ---------------------------------------------------------------------------
CALL _unitrade_add_column('t_order', 'origin_price',
    'origin_price DECIMAL(10,2) NULL COMMENT ''下单时的商品挂牌价（快照）'' AFTER product_id');
CALL _unitrade_add_column('t_order', 'deal_price',
    'deal_price DECIMAL(10,2) NULL COMMENT ''实际成交价（议价达成时为议价价）'' AFTER origin_price');

-- ---------------------------------------------------------------------------
-- 2. P0-E：退款前状态
--
-- rejectRefund 原来固定回退到"已付款(2)"，导致从"已发货(3)"申请的退款
-- 被拒绝后，订单错误地回到"已付款"，发货记录丢失。
-- ---------------------------------------------------------------------------
CALL _unitrade_add_column('t_order', 'status_before_refund',
    'status_before_refund TINYINT NULL COMMENT ''申请退款前的订单状态，拒绝退款时按其回退'' AFTER status');

DROP PROCEDURE IF EXISTS _unitrade_add_column;

-- ---------------------------------------------------------------------------
-- 3. 回填历史订单的价格快照
--
-- ⚠️ 诚实说明：历史订单的真实成交价已经无法还原（原表根本没存这个字段），
--    这里用商品当前价兜底。如果该商品在此期间被改过价，回填值与当时实际不符。
--    新产生的订单不会再出现这个问题。
-- ---------------------------------------------------------------------------
UPDATE t_order o
JOIN t_product p ON p.id = o.product_id
SET o.origin_price = p.price,
    o.deal_price   = p.price
WHERE o.origin_price IS NULL OR o.deal_price IS NULL;

-- ---------------------------------------------------------------------------
-- 4. P0-B：数据订正 —— 区分「已售出(2)」与「已下架(3)」
--
-- 背景：Product 的注释定义 3 = 已下架，但 OrderServiceImpl.pay() 把"已售出"
--       也写成了 3。结果库里 status=3 混着两种语义，已经无法区分。
-- 判定口径：存在进行中/已完成订单（1待付款 2已付款 3已发货 4已完成 6退款中）
--           的商品一定是"已售出/交易中"，订正为 2；
--           没有任何关联订单的，视为卖家主动下架，保持 3。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS _bak_product_status_20260923;
CREATE TABLE _bak_product_status_20260923 AS
SELECT id, status AS status_before, NOW() AS backed_up_at FROM t_product;

UPDATE t_product p
SET p.status = 2
WHERE p.status = 3
  AND EXISTS (
      SELECT 1 FROM t_order o
      WHERE o.product_id = p.id AND o.status IN (1, 2, 3, 4, 6)
  );

-- ---------------------------------------------------------------------------
-- 5. 自检输出
-- ---------------------------------------------------------------------------
SELECT '--- t_product 状态分布（订正后）---' AS section;
SELECT status,
       CASE status WHEN 1 THEN '在售' WHEN 2 THEN '已售出/交易中' WHEN 3 THEN '已下架' ELSE '未知' END AS meaning,
       COUNT(*) AS cnt
FROM t_product GROUP BY status ORDER BY status;

SELECT '--- 本次订正的记录（前=订正前，后=订正后）---' AS section;
SELECT b.id, b.status_before, p.status AS status_after, LEFT(p.title, 20) AS title
FROM _bak_product_status_20260923 b
JOIN t_product p ON p.id = b.id
WHERE b.status_before <> p.status;

SELECT '--- t_order（含新列）---' AS section;
SELECT id, product_id, status, origin_price, deal_price, status_before_refund FROM t_order ORDER BY id;
