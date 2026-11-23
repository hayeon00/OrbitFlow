package com.finalproj.orbitflow.global.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @author : rlagkdus
 * @filename : ChatbotConfig
 * @since : 2025. 12. 30. 화요일
 */
@Configuration
public class ChatbotConfig {

    @Value("${OPENAI_API_KEY}")
    private String apiKey;

    @Value("${chroma.scheme:http}")
    private String scheme;

    @Value("${chroma.host:localhost}")
    private String host;

    @Value("${chroma.port:8000}")
    private int port;

    // 삭제 등 다른 경로와 같은 컬렉션을 쓰도록 설정값을 단일 출처로 사용
    @Value("${chroma.collection:orbitflow_manuals}")
    private String collectionName;


    /**
     * 1️⃣ 임베딩 모델 (텍스트 → 벡터)
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small")
                .timeout(Duration.ofSeconds(20))
                .maxRetries(2)
                .build();
    }

    /**
     * 2️⃣ 벡터 저장소 (ChromaDB)
     * - 로컬: http://localhost:8000
     * - K8s : http://chromadb:8000
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        String baseUrl = scheme + "://" + host + ":" + port;

        return ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collectionName)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 3️⃣ 채팅 모델
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.0)
                // 기본값(60s, 재시도 3회)이면 장애 시 요청 하나가 수 분간 DB 커넥션과 스레드를 점유한다
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
    }
}