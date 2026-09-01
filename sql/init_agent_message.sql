-- UniTrade Agent 对话历史消息表
-- 用于持久化 AI 助手的多轮对话，支撑会话恢复、上下文裁剪与会话隔离
CREATE TABLE IF NOT EXISTS `t_agent_message` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话ID，不同会话天然隔离',
  `role`        VARCHAR(16)  NOT NULL COMMENT '消息角色：user/assistant',
  `content`     TEXT                  COMMENT '消息内容',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_session` (`session_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 助手对话历史';