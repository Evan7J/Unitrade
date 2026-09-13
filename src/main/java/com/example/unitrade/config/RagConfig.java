package com.example.unitrade.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义检索配置：纯 Java 本地向量化，替代原 Python(semantic-search) 的 embedding + FAISS。
 * - EmbeddingModel：spring-ai transformers 本地 ONNX 模型，零 API key
 * - VectorStore：SimpleVectorStore 内存向量库，支持持久化到本地磁盘 JSON
 *
 * 模型资源默认走中文多语言多语言模型 paraphrase-multilingual-MiniLM-L12-v2（hf-mirror 国内镜像）；
 * 若需改回 spring-ai 官方 all-MiniLM（英文），同步改 application.yml 里 app.vector.model-url / tokenizer-url。
 */
@Configuration
public class RagConfig {

    private static final String DEFAULT_MODEL_URL =
            "https://hf-mirror.com/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model.onnx";
    private static final String DEFAULT_TOKENIZER_URL =
            "https://hf-mirror.com/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json";

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.transformers.cache-directory:${java.io.tmpdir}/spring-ai-transformer-cache}") String cacheDir,
            @Value("${app.vector.model-url:" + RagConfig.DEFAULT_MODEL_URL + "}") String modelUrl,
            @Value("${app.vector.tokenizer-url:" + RagConfig.DEFAULT_TOKENIZER_URL + "}") String tokenizerUrl) {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setResourceCacheDirectory(cacheDir);
        model.setModelResource(modelUrl);
        model.setTokenizerResource(tokenizerUrl);
        model.setModelOutputName("last_hidden_state");
        return model;
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}