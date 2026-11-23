package com.finalproj.orbitflow.chatbot.manual.service;

import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorService;
import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manualcategory.entity.ManualCategory;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import com.finalproj.orbitflow.global.exception.InvalidRequestException;
import com.finalproj.orbitflow.global.file.entity.File;
import com.finalproj.orbitflow.global.file.service.FileService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 매뉴얼 업로드/삭제/활성화와 벡터 저장소의 동기화 규칙 검증.
 */
class ManualSyncServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long FILE_ID = 77L;

    private ManualRepository manualRepository;
    private ManualCategoryRepository categoryRepository;
    private EmployeeRepository employeeRepository;
    private FileService fileService;
    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;
    private ChromaVectorService chromaVectorService;
    private ManualUploadService service;

    private Company company;
    private ManualCategory category;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manualRepository = mock(ManualRepository.class);
        categoryRepository = mock(ManualCategoryRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        fileService = mock(FileService.class);
        embeddingModel = mock(EmbeddingModel.class);
        embeddingStore = mock(EmbeddingStore.class);
        chromaVectorService = mock(ChromaVectorService.class);

        service = new ManualUploadService(manualRepository, categoryRepository, employeeRepository,
                manualRepository, fileService, embeddingModel, embeddingStore, chromaVectorService);

        company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        Employee employee = mock(Employee.class);
        when(employee.getCompany()).thenReturn(company);
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        category = mock(ManualCategory.class);
        when(category.getId()).thenReturn(CATEGORY_ID);
        when(categoryRepository.findByIdAndCompanyIdAndIsActiveTrue(CATEGORY_ID, COMPANY_ID))
                .thenReturn(Optional.of(category));
    }

    private ManualMetadata manual(String status, boolean active) {
        ManualMetadata m = ManualMetadata.builder()
                .id(5L)
                .company(company)
                .category(category)
                .file(File.builder().id(FILE_ID).objectKey("chat/77").build())
                .fileName("security.txt")
                .filePath("chat/77")
                .status(status)
                .isActive(active)
                .build();
        when(manualRepository.findById(5L)).thenReturn(Optional.of(m));
        when(manualRepository.findByIdAndCompanyId(5L, COMPANY_ID)).thenReturn(Optional.of(m));
        return m;
    }

    // ---------------------------------------------------------------- 삭제

    @Test
    void 삭제하면_비활성_DELETE_상태가_되고_해당_파일의_벡터를_삭제한다() {
        ManualMetadata m = manual("SUCCESS", true);

        service.deleteManual(5L, COMPANY_ID);

        assertThat(m.getIsActive()).isFalse();
        assertThat(m.getStatus()).isEqualTo("DELETE");
        verify(chromaVectorService).deleteByFileId(COMPANY_ID, FILE_ID.toString());
    }

    @Test
    void 벡터_삭제가_실패해도_매뉴얼은_삭제_상태로_남는다() {
        ManualMetadata m = manual("SUCCESS", true);
        doThrow(new RuntimeException("chroma down"))
                .when(chromaVectorService).deleteByFileId(any(), any());

        service.deleteManual(5L, COMPANY_ID);

        // 검색은 활성 file_id 만 허용하므로 남은 벡터가 답변에 쓰이지 않는다
        assertThat(m.getIsActive()).isFalse();
        assertThat(m.getStatus()).isEqualTo("DELETE");
    }

    @Test
    void 다른_회사의_매뉴얼은_삭제할_수_없다() {
        manual("SUCCESS", true);

        assertThatThrownBy(() -> service.deleteManual(5L, 999L))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(chromaVectorService);
    }

    // ---------------------------------------------------------------- 활성화

    @Test
    void 삭제된_매뉴얼은_다시_활성화할_수_없다() {
        ManualMetadata m = manual("DELETE", false);

        assertThatThrownBy(() -> service.updateManualActive(COMPANY_ID, 5L, true))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(m.getIsActive()).isFalse();
    }

    @Test
    void 비활성화한_매뉴얼은_다시_활성화할_수_있다() {
        ManualMetadata m = manual("SUCCESS", false);

        service.updateManualActive(COMPANY_ID, 5L, true);

        assertThat(m.getIsActive()).isTrue();
    }

    @Test
    void 관리자_목록은_삭제되지_않은_비활성_매뉴얼도_조회한다() {
        service.findAllByCompany(COMPANY_ID);
        service.findByCompanyAndCategory(COMPANY_ID, CATEGORY_ID);

        verify(manualRepository).findNotDeletedByCompany(COMPANY_ID, "DELETE");
        verify(manualRepository).findNotDeletedByCompanyAndCategory(COMPANY_ID, CATEGORY_ID, "DELETE");
    }

    // ---------------------------------------------------------------- 업로드

    @Test
    void 확장자가_없는_파일은_500이_아니라_검증_오류로_거절한다() {
        var file = new MockMultipartFile("file", "manual", "text/plain", "내용".getBytes());

        assertThatThrownBy(() -> service.uploadAndIndexingManual(file, CATEGORY_ID, EMPLOYEE_ID))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(fileService, embeddingStore);
    }

    @Test
    void 텍스트가_없는_문서는_저장소에_아무것도_남기지_않고_거절한다() throws Exception {
        var file = new MockMultipartFile("file", "blank.txt", "text/plain", "   \n  ".getBytes());

        assertThatThrownBy(() -> service.uploadAndIndexingManual(file, CATEGORY_ID, EMPLOYEE_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("텍스트를 추출할 수 없습니다");
        verify(fileService, never()).upload(any(), any(), any());
        verifyNoInteractions(embeddingStore);
        verify(manualRepository, never()).save(any());
    }

    @Test
    void 정상_업로드시_청크마다_회사_카테고리_파일_메타데이터를_붙이고_마지막에_메타데이터를_저장한다() throws Exception {
        when(fileService.upload(eq(COMPANY_ID), any(), any()))
                .thenReturn(File.builder().id(FILE_ID).objectKey("chat/77").build());
        when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<TextSegment> segs = inv.getArgument(0);
            return Response.from(segs.stream().map(s -> Embedding.from(new float[]{1f, 0f})).toList());
        });
        var file = new MockMultipartFile("file", "m.txt", "text/plain", "지각 기준은 9시 10분입니다.".getBytes());

        service.uploadAndIndexingManual(file, CATEGORY_ID, EMPLOYEE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertThat(captor.getValue()).allSatisfy(seg -> {
            assertThat(seg.metadata().toMap()).containsEntry("company_id", "1")
                    .containsEntry("category_id", "3")
                    .containsEntry("file_id", "77");
        });

        ArgumentCaptor<ManualMetadata> saved = ArgumentCaptor.forClass(ManualMetadata.class);
        verify(manualRepository).save(saved.capture());
        assertThat(saved.getValue().getIsActive()).isTrue();
        assertThat(saved.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void 임베딩이_실패하면_벡터와_원본파일을_정리하고_메타데이터를_저장하지_않는다() throws Exception {
        when(fileService.upload(eq(COMPANY_ID), any(), any()))
                .thenReturn(File.builder().id(FILE_ID).objectKey("chat/77").build());
        when(embeddingModel.embedAll(anyList())).thenThrow(new RuntimeException("openai timeout"));
        var file = new MockMultipartFile("file", "m.txt", "text/plain", "내용입니다.".getBytes());

        assertThatThrownBy(() -> service.uploadAndIndexingManual(file, CATEGORY_ID, EMPLOYEE_ID))
                .isInstanceOf(InvalidRequestException.class);

        verify(chromaVectorService).deleteByFileId(COMPANY_ID, FILE_ID.toString());
        verify(fileService).deleteObject("chat/77");
        verify(manualRepository, never()).save(any());
    }
}
