package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error(400, "文件不能为空");
        String ext = file.getOriginalFilename() != null
                ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")) : ".jpg";
        String filename = UUID.randomUUID().toString() + ext;
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                return Result.error(500, "上传目录创建失败，请联系管理员检查服务器配置");
            }
        }
        try {
            file.transferTo(new File(dir, filename));
        } catch (IOException e) {
            return Result.error(500, "上传失败: " + e.getMessage());
        }
        Map<String, String> map = new HashMap<>();
        map.put("url", "/uploads/" + filename);
        return Result.success(map);
    }

    @PostMapping("/images")
    public Result<List<String>> uploadImages(@RequestParam("files") MultipartFile[] files) {
        List<String> urls = new ArrayList<>();
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                return Result.error(500, "上传目录创建失败，请联系管理员检查服务器配置");
            }
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String ext = file.getOriginalFilename() != null
                    ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")) : ".jpg";
            String filename = UUID.randomUUID().toString() + ext;
            try {
                file.transferTo(new File(dir, filename));
                urls.add("/uploads/" + filename);
            } catch (IOException e) {
                return Result.error(500, "上传失败: " + e.getMessage());
            }
        }
        return Result.success(urls);
    }
}