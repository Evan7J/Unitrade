package com.example.unitrade.service;

import com.example.unitrade.entity.Announcement;
import java.util.List;

/**
 * 公告服务接口
 */
public interface AnnouncementService {
    List<Announcement> listAll();
    Announcement getById(Long id);
    void save(Announcement announcement);
    void update(Announcement announcement);
    void delete(Long id);
}
