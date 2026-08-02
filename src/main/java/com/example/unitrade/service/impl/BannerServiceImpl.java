package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.entity.Banner;
import com.example.unitrade.mapper.BannerMapper;
import com.example.unitrade.service.BannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BannerServiceImpl implements BannerService {
    private final BannerMapper bannerMapper;
    @Override
    public List<Banner> listAll() {
        return bannerMapper.selectList(new LambdaQueryWrapper<Banner>().orderByAsc(Banner::getSortOrder));
    }
    @Override
    public Banner getById(Long id) { return bannerMapper.selectById(id); }
    @Override
    public void save(Banner b) { bannerMapper.insert(b); }
    @Override
    public void update(Banner b) { bannerMapper.updateById(b); }
    @Override
    public void delete(Long id) { bannerMapper.deleteById(id); }
}