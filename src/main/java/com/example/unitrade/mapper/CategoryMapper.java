package com.example.unitrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.unitrade.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类表 Mapper
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}