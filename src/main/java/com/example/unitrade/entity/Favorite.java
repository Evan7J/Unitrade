package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏实体类，对应数据库 t_favorite 表
 */
@Data
@TableName("t_favorite")
public class Favorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收藏用户ID */
    private Long userId;

    /** 商品ID */
    private Long productId;

    private LocalDateTime createTime;
}