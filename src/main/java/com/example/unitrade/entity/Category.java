package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类实体类，对应数据库 t_category 表
 */
@Data
@TableName("t_category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名称：数码产品、书籍教材、服饰鞋包等 */
    private String name;

    /** 排序号，数字越小越靠前 */
    private Integer sortOrder;

    private LocalDateTime createTime;
}