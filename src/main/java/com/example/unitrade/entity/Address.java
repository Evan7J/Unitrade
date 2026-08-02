package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收货地址实体类，对应数据库 t_address 表
 */
@Data
@TableName("t_address")
public class Address {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 收货人姓名 */
    private String receiverName;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detail;

    /** 联系电话 */
    private String phone;

    private LocalDateTime createTime;
}