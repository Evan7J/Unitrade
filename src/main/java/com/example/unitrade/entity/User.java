package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体类，对应数据库 t_user 表
 *
 * role（角色）：
 *   user  = 普通用户（默认），可以买卖商品、聊天
 *   admin = 管理员，登录后台管理系统
 */
@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String password;
    private String nickname;
    private String avatarUrl;
    private String school;
    /** 个人简介 */
    private String bio;

    /** 角色：user 普通用户，admin 管理员 */
    private String role;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}