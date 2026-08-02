package com.example.unitrade.service;

import com.example.unitrade.entity.Banner;
import java.util.List;

/**
 * 轮播图服务接口
 */
public interface BannerService {
    List<Banner> listAll();
    Banner getById(Long id);
    void save(Banner banner);
    void update(Banner banner);
    void delete(Long id);
}
