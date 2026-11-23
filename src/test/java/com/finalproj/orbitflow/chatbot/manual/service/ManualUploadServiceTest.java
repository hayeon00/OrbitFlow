
package com.finalproj.orbitflow.chatbot.manual.service;

import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorService;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;
import com.finalproj.orbitflow.global.exception.InvalidRequestException;
import com.finalproj.orbitflow.global.file.service.FileService;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualUploadServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long EMPLOYEE_ID = 10L;
    private static final Long CATEGORY_ID = 99L;

    @Mock
    private ManualRepository manualMetadataRepository;

    @Mock
    private ManualCategoryRepository manualCategoryRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private FileService fileService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    private ChromaVectorService chromaVectorService;

    @InjectMocks
    private ManualUploadService manualUploadService;

    @BeforeEach
    void setUp() {

        Employee employee = mock(Employee.class);
        Company company = mock(Company.class);

        when(employeeRepository.findById(EMPLOYEE_ID))
                .thenReturn(Optional.of(employee));

        when(employee.getCompany()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
    }

    @Test
    void 다른_회사의_카테고리로는_매뉴얼을_업로드할_수_없다() {

        // 회사 1의 직원이 회사 2의 카테고리 ID를 전달한 상황.
        // 회사 1 조건으로 조회하면 결과가 없어야 한다.
        when(manualCategoryRepository
                .findByIdAndCompanyIdAndIsActiveTrue(
                        CATEGORY_ID,
                        COMPANY_ID
                )).thenReturn(Optional.empty());

        var file = new MockMultipartFile(
                "file",
                "manual.txt",
                "text/plain",
                "테스트 매뉴얼".getBytes()
        );

        assertThrows(
                InvalidRequestException.class,
                () -> manualUploadService.uploadAndIndexingManual(
                        file,
                        CATEGORY_ID,
                        EMPLOYEE_ID
                )
        );

        verify(manualCategoryRepository)
                .findByIdAndCompanyIdAndIsActiveTrue(
                        CATEGORY_ID,
                        COMPANY_ID
                );

        // 검증 실패 이후 외부 시스템에 부작용이 없어야 한다.
        verifyNoInteractions(
                fileService,
                embeddingModel,
                embeddingStore,
                chromaVectorService
        );

        verify(manualMetadataRepository, never())
                .save(any());
    }

    @Test
    void 비활성_카테고리로는_매뉴얼을_업로드할_수_없다() {

        when(manualCategoryRepository
                .findByIdAndCompanyIdAndIsActiveTrue(
                        CATEGORY_ID,
                        COMPANY_ID
                )).thenReturn(Optional.empty());

        var file = new MockMultipartFile(
                "file",
                "manual.txt",
                "text/plain",
                "테스트 매뉴얼".getBytes()
        );

        assertThrows(
                InvalidRequestException.class,
                () -> manualUploadService.uploadAndIndexingManual(
                        file,
                        CATEGORY_ID,
                        EMPLOYEE_ID
                )
        );

        verifyNoInteractions(
                fileService,
                embeddingModel,
                embeddingStore,
                chromaVectorService
        );

        verify(manualMetadataRepository, never())
                .save(any());
    }
}
