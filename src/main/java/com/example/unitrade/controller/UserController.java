package com.example.unitrade.controller;

import com.example.unitrade.common.Result;
import com.example.unitrade.dto.LoginDTO;
import com.example.unitrade.dto.RegisterDTO;
import com.example.unitrade.entity.User;
import com.example.unitrade.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success("注册成功");
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginDTO dto) {
        String token = userService.login(dto);
        User user = userService.getByPhone(dto.getPhone());
        Map<String, Object> map = new HashMap<>();
        map.put("token", token);
        map.put("role", user.getRole());
        return Result.success("登录成功", map);
    }

    @GetMapping("/profile")
    public Result<User> profile() {
        return Result.success(userService.getProfile());
    }

    @PutMapping("/profile")
    public Result<?> updateProfile(@RequestBody User user) {
        userService.updateProfile(user);
        return Result.success("更新成功");
    }

    @GetMapping("/info/{id}")
    public Result<User> getUserInfo(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }
}