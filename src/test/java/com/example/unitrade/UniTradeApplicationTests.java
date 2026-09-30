package com.example.unitrade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载冒烟测试。
 *
 * <p><b>为什么必须指定 RANDOM_PORT</b>：
 * 默认的 {@code WebEnvironment.MOCK} 不会启动真实的 Servlet 容器，
 * 于是 {@code WebSocketConfig} 里的 {@code ServerEndpointExporter} 拿不到
 * {@code jakarta.websocket.server.ServerContainer}，直接抛
 * {@code ServerContainer not available} 把整个上下文刷失败 ——
 * 这会让 {@code mvn test} 长期是红的，而<b>红色的构建等于没有构建</b>：
 * 真出现回归时，没人分得清是新问题还是这个老问题。
 *
 * <p>用随机端口起一个真实容器，才能让 WebSocket 端点注册走通。
 * 代价是这个测试依赖外部基础设施（MySQL / Redis），属于集成测试——
 * 所以定价内核那些纯逻辑测试一律不放在这里：它们不依赖 Spring，也不依赖数据库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UniTradeApplicationTests {

    @Test
    void contextLoads() {
    }
}
