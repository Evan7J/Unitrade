package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 助手对话历史消息（t_agent_message）
 * 支撑多轮会话持久化、上下文裁剪与会话隔离
 */
@Data
@TableName("t_agent_message")
public class AgentMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID，不同会话天然隔离，互不可见 */
    private String sessionId;

    /** 消息角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    private LocalDateTime createTime;
}