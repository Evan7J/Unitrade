package com.example.unitrade.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置类
 *
 * 三大职责：
 * 1. 配置 JWT 拦截器（哪些接口需要登录，哪些不需要）
 * 2. 配置跨域 CORS（前端 localhost:5173 可以访问后端 localhost:8080）
 * 3. 配置静态资源映射（上传的图片可以通过 URL 直接访问）
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final RateLimiterInterceptor rateLimiterInterceptor;

    /** 上传文件目录，从配置文件注入，与 UploadController 保持一致 */
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    /**
     * 拦截器配置
     *
     * 执行顺序：限流拦截器 → JWT 拦截器
     * - 限流拦截器先执行，拦截恶意高频请求，减少不必要的 JWT 解析
     * - JWT 拦截器后执行，验证用户身份
     *
     * /api/** 所有接口都需要登录
     * 但注册、登录、首页商品列表、分类列表、商品详情 不需要登录
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 限流拦截器：对所有 /api/** 生效（不论是否登录）
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/api/**")
                .order(0); // 最先执行

        // JWT 认证拦截器
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/register",
                        "/api/user/login",
                        "/api/category/list",
                        "/api/product/list",
                        "/api/product/detail/*",
                        "/api/announcement/list",
                        "/api/banner/list"
                )
                .order(1); // 限流之后执行
    }

    /**
     * 跨域配置
     * 允许前端开发服务器（Vite 默认 5173）访问后端
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 静态资源映射
     * 映射后通过 http://localhost:8080/uploads/xxx.jpg 访问
     * 路径从 application.yml 的 file.upload-dir 注入，保证与上传目录一致
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}