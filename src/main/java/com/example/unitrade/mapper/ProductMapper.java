package com.example.unitrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.unitrade.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品表 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}