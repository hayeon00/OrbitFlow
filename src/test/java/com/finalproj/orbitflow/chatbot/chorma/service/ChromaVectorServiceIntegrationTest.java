package com.finalproj.orbitflow.chatbot.chorma.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Chroma(운영과 같은 0.4.21)에 대해 매뉴얼 벡터 삭제가 동작하는지 검증한다.
 * CHROMA_IT_URL=http://localhost:18000 ./gradlew test --tests '*ChromaVectorServiceIntegrationTest*'
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_IT_URL", matches = "http.*")
class ChromaVectorServiceIntegrationTest {

    @Test
    void 파일ID와_회사ID가_모두_일치하는_벡터만_삭제한다() {
        EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
                .baseUrl(System.getenv("CHROMA_IT_URL"))
                .collectionName("orbitflow_it_" + System.currentTimeMillis())
                .timeout(Duration.ofSeconds(10))
                .build();

        add(store, "1", "7", "회사1 파일7 청크A");
        add(store, "1", "7", "회사1 파일7 청크B");
        add(store, "1", "8", "회사1 파일8");
        add(store, "2", "7", "회사2 파일7 (같은 file_id, 다른 회사)");

        new ChromaVectorServiceImpl(store).deleteByFileId(1L, "7");

        assertThat(texts(store)).containsExactlyInAnyOrder("회사1 파일8", "회사2 파일7 (같은 file_id, 다른 회사)");
    }

    private static void add(EmbeddingStore<TextSegment> store, String companyId, String fileId, String text) {
        store.add(Embedding.from(new float[]{1f, 0.5f, 0.25f}),
                TextSegment.from(text, Metadata.from(Map.of("company_id", companyId, "file_id", fileId))));
    }

    private static List<String> texts(EmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[]{1f, 0.5f, 0.25f}))
                        .maxResults(10).minScore(0.0)
                        .filter(metadataKey("company_id").isIn("1", "2"))
                        .build())
                .matches().stream().map(m -> m.embedded().text()).toList();
    }
}
