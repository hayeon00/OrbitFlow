package com.finalproj.orbitflow.chatbot.eval;

import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorService;
import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorServiceImpl;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manual.service.ManualUploadService;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import com.finalproj.orbitflow.global.file.service.FileService;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

/** 평가 하네스에서 서비스 객체를 조립한다 (생성자 변경 시 이 파일만 수정). */
final class EvalWiring {

    private EvalWiring() {
    }

    static ChromaVectorService vectorService(EmbeddingStore<TextSegment> store, String chromaUrl, String collectionName) {
        // 개선 전(baseline) 구현은 WebClient + 컬렉션 이름으로 delete API 를 호출했다:
        //   new ChromaVectorServiceImpl(WebClient...) + collectionName 주입
        return new ChromaVectorServiceImpl(store);
    }

    static ManualUploadService uploadService(ManualRepository manualRepository,
                                             ManualCategoryRepository categoryRepository,
                                             EmployeeRepository employeeRepository,
                                             FileService fileService,
                                             EmbeddingModel embeddingModel,
                                             EmbeddingStore<TextSegment> store,
                                             ChromaVectorService vectorService) {
        return new ManualUploadService(manualRepository, categoryRepository, employeeRepository,
                manualRepository, fileService, embeddingModel, store, vectorService);
    }
}
