package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Announcement;
import com.example.unitrade.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping("/list")
    public Result<List<Announcement>> list() { return Result.success(announcementService.listAll()); }

    @GetMapping("/{id}")
    public Result<Announcement> getById(@PathVariable Long id) { return Result.success(announcementService.getById(id)); }

    @PostMapping("/save")
    public Result<?> save(@RequestBody Announcement a) { announcementService.save(a); return Result.success("添加成功"); }

    @PutMapping("/update")
    public Result<?> update(@RequestBody Announcement a) { announcementService.update(a); return Result.success("更新成功"); }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { announcementService.delete(id); return Result.success("删除成功"); }
}