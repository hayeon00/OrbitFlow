package com.finalproj.orbitflow.chatbot.chorma.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 매뉴얼 벡터 삭제.
 *
 * <p>이전 구현은 WebClient 로 {@code /api/v1/collections/{collectionName}/delete} 를 직접 호출했지만,
 * Chroma(0.4.x) 의 delete API 는 컬렉션 "이름"이 아니라 "UUID"를 요구하므로 항상 400(InvalidUUID)이 발생했고
 * 호출부에서 예외를 삼켜 벡터가 실제로는 한 번도 삭제되지 않았다.
 * 검색에 사용하는 것과 같은 {@link EmbeddingStore} 의 {@code removeAll(Filter)} 를 사용하면
 * 컬렉션 ID 해석을 라이브러리가 처리하므로 같은 컬렉션을 확실히 대상으로 한다.</p>
 *
 * @author : rlagkdus
 * @filename : ChromaVectorServiceImpl
 * @since : 2026. 1. 15. 목요일
 */
@Service
@RequiredArgsConstructor
public class ChromaVectorServiceImpl implements ChromaVectorService {

    private final EmbeddingStore<TextSegment> embeddingStore;

    @Override
    public void deleteByFileId(Long companyId, String fileId) {
        Filter filter = metadataKey("company_id").isEqualTo(companyId.toString())
                .and(metadataKey("file_id").isEqualTo(fileId));

        embeddingStore.removeAll(filter);
    }
}
