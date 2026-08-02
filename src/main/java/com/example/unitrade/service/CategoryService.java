package com.example.unitrade.service;

import com.example.unitrade.entity.Category;

import java.util.List;

/**
 * 分类服务接口
 */
public interface CategoryService {

    /**
     * 查询所有分类，按 sortOrder 升序排列
     */
    List<Category> listAll();
}