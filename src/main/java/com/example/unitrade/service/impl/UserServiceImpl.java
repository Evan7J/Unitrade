package com.example.unitrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.unitrade.common.BusinessException;
import com.example.unitrade.config.JwtInterceptor;
import com.example.unitrade.dto.LoginDTO;
import com.example.unitrade.dto.RegisterDTO;
import com.example.unitrade.entity.User;
import com.example.unitrade.mapper.UserMapper;
import com.example.unitrade.service.UserService;
import com.example.unitrade.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone())
        );
        if (count > 0) {
            throw new BusinessException("手机号已注册");
        }
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() != null ? dto.getNickname() : "用户" + dto.getPhone().substring(7));
        user.setRole("user");
        userMapper.insert(user);
    }

    @Override
    public String login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone())
        );
        // 统一提示"账号或密码错误"，防止撞库攻击（不区分账号不存在/密码错误）
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        return jwtUtil.generateToken(user.getId());
    }

    @Override
    public User getProfile() {
        Long userId = JwtInterceptor.getCurrentUserId();
        User user = userMapper.selectById(userId);
        user.setPassword(null);
        return user;
    }

    @Override
    public void updateProfile(User user) {
        Long userId = JwtInterceptor.getCurrentUserId();
        User dbUser = userMapper.selectById(userId);
        if (user.getNickname() != null) dbUser.setNickname(user.getNickname());
        if (user.getAvatarUrl() != null) dbUser.setAvatarUrl(user.getAvatarUrl());
        if (user.getSchool() != null) dbUser.setSchool(user.getSchool());
        if (user.getBio() != null) dbUser.setBio(user.getBio());
        userMapper.updateById(dbUser);
    }

    @Override
    public User getByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }

    @Override
    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user != null) user.setPassword(null);
        return user;
    }
}
