-- =============================================================================
-- 议价 Agent · token 成本埋点（要点 5）
-- -----------------------------------------------------------------------------
-- 为什么要落库而不是只打日志：
--   ¥0.12 / 次、"较全量强模型下降 60%" 这两个数字必须【可复现、可追溯】。
--   日志会滚、会丢、没法聚合；落在轮次表里才能一条 SQL 算出平均值，
--   并且事后能回答"这个数字是哪几次调用算出来的"。
--
-- 幂等：先查 information_schema 再动态 ALTER（MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）
-- =============================================================================

USE campus_trade;

SET @db = 'campus_trade';

-- ① prompt_tokens：输入 token 数
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=@db AND TABLE_NAME='t_negotiation_round' AND COLUMN_NAME='prompt_tokens');
SET @sql := IF(@exists = 0,
    'ALTER TABLE t_negotiation_round ADD COLUMN prompt_tokens INT DEFAULT 0 COMMENT ''本轮输入 token 数'' AFTER agent_reply',
    'SELECT ''prompt_tokens 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② completion_tokens：输出 token 数
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=@db AND TABLE_NAME='t_negotiation_round' AND COLUMN_NAME='completion_tokens');
SET @sql := IF(@exists = 0,
    'ALTER TABLE t_negotiation_round ADD COLUMN completion_tokens INT DEFAULT 0 COMMENT ''本轮输出 token 数'' AFTER prompt_tokens',
    'SELECT ''completion_tokens 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ token_cost：本轮成本（元）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=@db AND TABLE_NAME='t_negotiation_round' AND COLUMN_NAME='token_cost');
SET @sql := IF(@exists = 0,
    'ALTER TABLE t_negotiation_round ADD COLUMN token_cost DECIMAL(12,6) DEFAULT 0 COMMENT ''本轮 LLM 成本（元）'' AFTER completion_tokens',
    'SELECT ''token_cost 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ④ model_name：本轮实际用的模型（口径可追溯的关键：换模型后历史数据仍知道是哪次）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=@db AND TABLE_NAME='t_negotiation_round' AND COLUMN_NAME='model_name');
SET @sql := IF(@exists = 0,
    'ALTER TABLE t_negotiation_round ADD COLUMN model_name VARCHAR(64) DEFAULT NULL COMMENT ''本轮实际调用的模型'' AFTER token_cost',
    'SELECT ''model_name 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================ 自检输出 ============================
SELECT '===== t_negotiation_round token 埋点列 =====' AS section;
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=@db AND TABLE_NAME='t_negotiation_round'
  AND COLUMN_NAME IN ('prompt_tokens','completion_tokens','token_cost','model_name')
ORDER BY ORDINAL_POSITION;

SELECT '===== 已有轮次的 token 汇总（埋点前应为全 0）=====' AS section;
SELECT COUNT(*) AS 总轮次,
       IFNULL(SUM(prompt_tokens),0) AS 累计输入token,
       IFNULL(SUM(completion_tokens),0) AS 累计输出token,
       IFNULL(SUM(token_cost),0) AS 累计成本元
FROM t_negotiation_round;
