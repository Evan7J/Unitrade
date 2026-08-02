package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.entity.Banner;
import com.example.unitrade.service.BannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/banner")
@RequiredArgsConstructor
public class BannerController {

    private final BannerService bannerService;

    @GetMapping("/list")
    public Result<List<Banner>> list() { return Result.success(bannerService.listAll()); }

    @GetMapping("/{id}")
    public Result<Banner> getById(@PathVariable Long id) { return Result.success(bannerService.getById(id)); }

    @PostMapping("/save")
    public Result<?> save(@RequestBody Banner b) { bannerService.save(b); return Result.success("添加成功"); }

    @PutMapping("/update")
    public Result<?> update(@RequestBody Banner b) { bannerService.update(b); return Result.success("更新成功"); }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { bannerService.delete(id); return Result.success("删除成功"); }
}