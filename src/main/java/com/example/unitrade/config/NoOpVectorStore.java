package com.example.unitrade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.Collections;
import java.util.List;

/**
 * 向量库降级实现：Milvus 不可用时启用，保证应用照常启动。
 *
 * <h2>为什么必须要有这个类</h2>
 * 原来的代码在 {@code ProductVectorIndexer} 里写了 try-catch，注释说
 * "任何一步失败都只降级，不影响主流程启动"。但那个降级<b>从来没有生效过</b>，
 * 因为真正会抛异常的地方不在那个 ApplicationRunner 里，而在更早的
 * <b>bean 创建阶段</b>：{@code MilvusVectorStoreAutoConfiguration.milvusClient()}
 * 构造 {@code MilvusServiceClient} 时会真实建立 gRPC 连接，
 * 连不上直接抛 RuntimeException，整个 Spring 上下文刷新失败，应用起不来。
 *
 * <p>这就是"<b>降级写在了错误的生命周期位置</b>"：
 * <ul>
 *   <li>bean 创建期（这里是 Configure）失败 → 应用根本起不来，没人有机会 catch；</li>
 *   <li>ApplicationRunner 运行期失败 → 已经被 try-catch 包住了。</li>
 * </ul>
 * 所以降级点必须前移到装配阶段，也就是本包里的条件配置。
 *
 * <h2>为什么降级不是"什么都不做"</h2>
 * 每个方法都会打 WARN 日志。<b>悄悄降级等于质量无声下降</b> ——
 * 语义召回退化成关键词检索之后，召回率会变差，但如果没有日志和指标，
 * 你只会觉得"最近搜索怎么不太准"，永远定位不到根因。
 */
public class NoOpVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(NoOpVectorStore.class);

    @Override
    public void add(List<Document> documents) {
        log.warn("[向量检索降级] 忽略写入 {} 条文档：Milvus 未启用，语义召回已退化为纯关键词检索",
                documents == null ? 0 : documents.size());
    }

    @Override
    public void delete(List<String> ids) {
        log.debug("[向量检索降级] 忽略删除 {} 条文档", ids == null ? 0 : ids.size());
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        log.debug("[向量检索降级] 忽略按条件删除：{}", filterExpression);
    }

    /**
     * 返回空列表而不是抛异常。
     *
     * <p>调用方（AgentTools 的双路召回）拿到空结果后会自然退化为"只有关键词路有结果"，
     * 这正是我们想要的降级行为。如果这里抛异常，调用方要额外写 try-catch，
     * 而<b>能降级的功能不该要求每个调用点都记得处理失败</b>。
     */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        log.debug("[向量检索降级] 语义检索返回空结果，query={}",
                request == null ? null : request.getQuery());
        return Collections.emptyList();
    }
}
