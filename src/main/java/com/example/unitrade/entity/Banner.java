package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 轮播图实体类，对应数据库 t_banner 表
 */
@Data
@TableName("t_banner")
public class Banner {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 图片URL */
    private String imageUrl;

    /** 图片裁剪位置，如 "center top"、"50% 30%" */
    private String objectPosition;

    /** 排序号，数字越小越靠前 */
    private Integer sortOrder;

    private LocalDateTime createTime;
}