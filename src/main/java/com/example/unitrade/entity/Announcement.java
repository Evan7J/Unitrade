package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告实体类，对应数据库 t_announcement 表
 */
@Data
@TableName("t_announcement")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公告标题 */
    private String title;

    /** 公告内容（富文本 HTML） */
    private String content;

    private LocalDateTime createTime;
}