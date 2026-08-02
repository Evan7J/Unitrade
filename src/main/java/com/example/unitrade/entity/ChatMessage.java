package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体类，对应数据库 t_chat_message 表
 *
 * isRead（已读状态）：
 *   0 = 未读（默认），消息发送后对方未查看
 *   1 = 已读，对方打开聊天窗口后标记
 */
@Data
@TableName("t_chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发送者ID */
    private Long senderId;

    /** 接收者ID */
    private Long receiverId;

    /** 关联商品ID（从商品详情页发起聊天时有值） */
    private Long productId;

    /** 消息内容 */
    private String content;

    /** 消息类型：text 文字，image 图片 */
    private String messageType;

    /** 是否已读：0未读 1已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}