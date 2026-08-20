package com.example.unitrade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * UniTrade 校园闲置交易平台启动类
 *
 * @EnableCaching 开启 Spring Cache 注解支持（配合 Redis 实现商品详情缓存）
 */
@SpringBootApplication
@MapperScan("com.example.unitrade.mapper")
@EnableCaching
public class UniTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniTradeApplication.class, args);
    }



    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
