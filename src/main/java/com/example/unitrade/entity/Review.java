package com.example.unitrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_review")
public class Review {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long reviewerId;
    private Long revieweeId;
    private Long productId;
    private Integer rating;       // 1-5 星
    private String content;
    private LocalDateTime createTime;
}
