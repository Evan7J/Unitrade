package com.example.unitrade.service;

import com.example.unitrade.common.Result;
import com.example.unitrade.dto.LoginDTO;
import com.example.unitrade.dto.RegisterDTO;
import com.example.unitrade.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {
    /** 注册 */
    void register(RegisterDTO dto);
    /** 登录，返回 JWT token */
    String login(LoginDTO dto);
    /** 获取当前用户信息 */
    User getProfile();
    /** 更新个人信息 */
    void updateProfile(User user);
    /** 根据ID获取用户 */
    User getById(Long id);
    /** 根据手机号获取用户 */
    User getByPhone(String phone);
}
