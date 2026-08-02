package com.example.unitrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.unitrade.common.Result;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserMapper userMapper;

    @GetMapping("/list")
    public Result<Page<User>> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(User::getNickname, keyword).or().like(User::getPhone, keyword);
        }
        wrapper.orderByDesc(User::getCreateTime);
        Page<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        result.getRecords().forEach(u -> u.setPassword(null));
        return Result.success(result);
    }

    @DeleteMapping("/delete/{id}")
    public Result<?> delete(@PathVariable Long id) { userMapper.deleteById(id); return Result.success("删除成功"); }

    @PutMapping("/role/{id}")
    public Result<?> setRole(@PathVariable Long id, @RequestParam String role) {
        User u = userMapper.selectById(id);
        if (u != null) { u.setRole(role); userMapper.updateById(u); }
        return Result.success("角色更新成功");
    }
}