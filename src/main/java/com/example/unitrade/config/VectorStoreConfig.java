package com.example.unitrade.config;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 向量库的条件装配：Milvus 可达则用真库，不可达则降级。
 *
 * <h2>为什么需要"手动 @Import 自动配置"这种看起来别扭的写法</h2>
 * {@code MilvusVectorStoreAutoConfiguration} 由 starter 通过 spring.factories 自动加载，
 * 它自身<b>不带"Milvus 是否可达"的条件</b>——只要依赖存在、配置存在，它就会去建连接，
 * 连不上就抛异常。而 {@code @ConditionalOnProperty} 这类注解我们没法加到别人的自动配置类上。
 *
 * <p>所以做法是：
 * <ol>
 *   <li>在 {@code application.yml} 里用 {@code spring.autoconfigure.exclude} 把它排除掉，
 *       不让框架自动加载；</li>
 *   <li>在这里用 {@code @Conditional} 判断 Milvus 可达性，<b>可达时再把它
 *       {@code @Import} 进来</b>——它自带的其它条件（classpath、属性）依然会被正常评估。</li>
 * </ol>
 *
 * <p>代价是这层间接看起来不够直观，换来的是：<b>Milvus 没起时应用依然能启动</b>，
 * 而这是"前后端能跑通"的前提。
 */
@Configuration(proxyBeanMethods = false)
@Conditional(MilvusAvailabilityCondition.class)
@Import(MilvusVectorStoreAutoConfiguration.class)
class MilvusVectorStoreConfig {
    // 空壳：唯一的作用是"条件成立时才把 Milvus 自动配置引进来"
}

/**
 * 降级装配：Milvus 不可达时提供一个什么都不做的 VectorStore。
 *
 * <p>注入方（{@code AgentTools}）只依赖 {@code VectorStore} 接口，
 * 所以换实现不需要改任何一行业务代码——这就是面向接口注入在"降级"这件事上的回报。
 */
@Configuration(proxyBeanMethods = false)
@Conditional(MilvusAvailabilityCondition.Unavailable.class)
class FallbackVectorStoreConfig {

    @Bean
    VectorStore vectorStore() {
        return new NoOpVectorStore();
    }
}
