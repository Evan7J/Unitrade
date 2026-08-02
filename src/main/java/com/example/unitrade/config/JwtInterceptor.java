package com.example.unitrade.config;

import com.example.unitrade.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String token = request.getHeader("Authorization");


        if (token == null || !token.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录，请先登录\"}");
            return false;
        }


        token = token.substring(7);


        if (!jwtUtil.validateToken(token)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Token无效或已过期，请重新登录\"}");
            return false;
        }


        Long userId = jwtUtil.getUserIdFromToken(token);
        USER_ID_HOLDER.set(userId);

        return true; // 放行
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        USER_ID_HOLDER.remove();
    }

    public static Long getCurrentUserId() {
        return USER_ID_HOLDER.get();
    }
}