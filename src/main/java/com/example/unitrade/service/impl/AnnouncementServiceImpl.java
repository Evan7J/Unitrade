package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.entity.Announcement;
import com.example.unitrade.mapper.AnnouncementMapper;
import com.example.unitrade.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {
    private final AnnouncementMapper announcementMapper;
    @Override
    public List<Announcement> listAll() {
        return announcementMapper.selectList(
                new LambdaQueryWrapper<Announcement>().orderByDesc(Announcement::getCreateTime));
    }
    @Override
    public Announcement getById(Long id) { return announcementMapper.selectById(id); }
    @Override
    public void save(Announcement a) { announcementMapper.insert(a); }
    @Override
    public void update(Announcement a) { announcementMapper.updateById(a); }
    @Override
    public void delete(Long id) { announcementMapper.deleteById(id); }
}