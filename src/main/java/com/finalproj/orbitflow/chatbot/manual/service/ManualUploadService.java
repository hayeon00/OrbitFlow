package com.finalproj.orbitflow.chatbot.manual.service;

import com.finalproj.orbitflow.chatbot.chorma.service.ChromaVectorService;
import com.finalproj.orbitflow.chatbot.manual.entity.ManualMetadata;
import com.finalproj.orbitflow.chatbot.manual.repository.ManualRepository;
import com.finalproj.orbitflow.chatbot.manualcategory.entity.ManualCategory;
import com.finalproj.orbitflow.chatbot.manualcategory.repository.ManualCategoryRepository;

import com.finalproj.orbitflow.global.file.entity.File;
import com.finalproj.orbitflow.global.file.enums.FileDomain;
import com.finalproj.orbitflow.global.file.service.FileService;
import com.finalproj.orbitflow.global.exception.InvalidRequestException;
import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.hr.employee.entity.Employee;
import com.finalproj.orbitflow.hr.employee.repository.EmployeeRepository;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// HWP 파일 처리를 위한 import (라이브러리 추가 후 주석 해제)
// import kr.dogfoot.hwplib.object.HWPFile;
// import kr.dogfoot.hwplib.reader.HWPReader;
// import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
// import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 *
 * @author : rlagkdus
 * @filename : ManualUploadService
 * @since : 2025. 12. 30. 화요일
 */


@Service
@RequiredArgsConstructor
@Slf4j
public class ManualUploadService {

    private final ManualRepository manualMetadataRepository;
    private final ManualCategoryRepository manualCategoryRepository;
    private final EmployeeRepository employeeRepository;
    private final ManualRepository manualRepository;
    private final FileService fileService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChromaVectorService chromaVectorService;

    private static final String STATUS_DELETE = "DELETE";

    /**
     * 관리자 목록: 삭제(DELETE)되지 않은 매뉴얼 전체(비활성 포함).
     * 비활성 매뉴얼이 목록에서 사라지면 관리자가 다시 활성화할 방법이 없으므로 함께 보여준다.
     * 챗봇 검색은 ChatService 에서 활성 매뉴얼만 사용한다.
     */
    @Transactional(readOnly = true)
    public List<ManualMetadata> findAllByCompany(Long companyId) {
        return manualMetadataRepository.findNotDeletedByCompany(companyId, STATUS_DELETE);
    }


    @Transactional(readOnly = true)
    public List<ManualMetadata> findByCompanyAndCategory(Long companyId, Long categoryId) {
        return manualMetadataRepository.findNotDeletedByCompanyAndCategory(companyId, categoryId, STATUS_DELETE);
    }

    @Transactional(readOnly = true)
    public List<ManualCategory> findActiveCategoriesByCompany(Long companyId) {
        return manualCategoryRepository.findByCompanyIdAndIsActiveTrueOrderBySortOrderAsc(companyId);
    }

    @Transactional
    public void uploadAndIndexingManual(MultipartFile file, Long categoryId, Long employeeId) {


        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new NotFoundException("사원 정보를 찾을 수 없습니다."));

        Long companyId = employee.getCompany().getId();

// 해당 직원의 회사에 속하면서 활성화된 카테고리만 허용
        ManualCategory category = manualCategoryRepository
                .findByIdAndCompanyIdAndIsActiveTrue(categoryId, companyId)
                .orElseThrow(() ->
                        new InvalidRequestException(
                                "해당 회사에서 사용할 수 없는 매뉴얼 카테고리입니다."
                        ));


        if (file.isEmpty()) {
            throw new InvalidRequestException("파일이 비어있습니다.");
        }

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null) {
            throw new InvalidRequestException("파일명이 없습니다.");
        }

        String fileExtension = extractExtension(originalFileName);
        List<String> allowedExtensions = List.of(".txt", ".pdf", ".doc");

        if (!allowedExtensions.contains(fileExtension)) {
            throw new InvalidRequestException("TXT, PDF, DOC 파일만 업로드 가능합니다.");
        }

        // 1) 파싱/분할을 먼저 수행: 텍스트가 없거나 손상된 문서는 저장소에 아무것도 남기지 않고 거절
        List<TextSegment> segments;
        try {
            segments = parseAndSplit(file, fileExtension);
        } catch (IOException e) {
            throw new InvalidRequestException("파일 처리 중 오류: " + e.getMessage());
        }

        // 2) 파일 저장 → 3) 임베딩/인덱싱 → 4) 메타데이터 저장(활성화)
        // 메타데이터가 마지막이므로, 중간 실패 시 검색 허용 목록(활성 file_id)에 들어가지 않는다.
        File savedFile;
        try {
            savedFile = processFileStorage(file, employee);
        } catch (IOException e) {
            throw new InvalidRequestException("파일 처리 중 오류: " + e.getMessage());
        }

        try {
            indexSegments(segments, category, employee, savedFile);
        } catch (RuntimeException e) {
            // 보상 처리: 일부만 저장됐을 수 있는 벡터와 업로드된 원본 파일 정리
            log.error("[MANUAL_INDEXING_FAILED] companyId={}, fileId={}", companyId, savedFile.getId(), e);
            try {
                chromaVectorService.deleteByFileId(companyId, savedFile.getId().toString());
            } catch (Exception cleanupError) {
                log.error("[MANUAL_INDEXING_CLEANUP_FAILED] companyId={}, fileId={}",
                        companyId, savedFile.getId(), cleanupError);
            }
            fileService.deleteObject(savedFile.getObjectKey());
            throw new InvalidRequestException("매뉴얼 학습(임베딩) 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        }

        saveManualMetadata(file, category, employee, savedFile);
    }

    private File processFileStorage(MultipartFile file, Employee employee) throws IOException {
        return fileService.upload(
                employee.getCompany().getId(),
                FileDomain.CHAT,
                file);
    }

    private List<TextSegment> parseAndSplit(MultipartFile file, String fileExtension) throws IOException {
        Document document;
        try {
            switch (fileExtension) {
                case ".pdf":
                    DocumentParser pdfParser = new ApachePdfBoxDocumentParser();
                    document = pdfParser.parse(file.getInputStream());
                    break;

                case ".txt":
                    DocumentParser txtParser = new TextDocumentParser();
                    document = txtParser.parse(file.getInputStream());
                    break;

                case ".doc":
                    String docText = extractTextFromDoc(file.getInputStream());
                    if (docText == null || docText.isBlank()) {
                        throw new BlankDocumentException();
                    }
                    document = new Document(docText);
                    break;

                default:
                    throw new InvalidRequestException("지원하지 않는 파일 형식입니다.");
            }
        } catch (BlankDocumentException e) {
            // 스캔 이미지 PDF 등 텍스트가 없는 문서: 빈 매뉴얼이 "성공"으로 등록되지 않도록 차단
            throw new InvalidRequestException("문서에서 텍스트를 추출할 수 없습니다. 스캔 이미지가 아닌 텍스트 문서를 업로드해 주세요.");
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(500, 100);
        return splitter.split(document);
    }

    private void indexSegments(List<TextSegment> segments, ManualCategory category, Employee employee, File savedFile) {
        segments.forEach(segment -> {
            segment.metadata().add("company_id", employee.getCompany().getId().toString());
            segment.metadata().add("category_id", category.getId().toString());
            segment.metadata().add("file_id", savedFile.getId().toString()); // 파일 ID를 메타데이터로 저장
        });

        embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);
    }

    private String extractExtension(String originalFileName) {
        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0) {
            throw new InvalidRequestException("TXT, PDF, DOC 파일만 업로드 가능합니다.");
        }
        return originalFileName.substring(dot).toLowerCase();
    }


    private String extractTextFromDoc(InputStream inputStream) throws IOException {
        try {
            HWPFDocument document = new HWPFDocument(inputStream);
            WordExtractor extractor = new WordExtractor(document);
            String text = extractor.getText();
            extractor.close();
            document.close();
            return text;
        } catch (Exception e) {
            throw new IOException("DOC 파일 파싱 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private void saveManualMetadata(MultipartFile file, ManualCategory category, Employee employee, File savedFile) {

        ManualMetadata metadata = ManualMetadata.builder()
                .company(employee.getCompany())
                .category(category)
                .file(savedFile)
                .fileName(file.getOriginalFilename())
                .filePath(savedFile.getObjectKey())
                .status("SUCCESS")
                .isActive(true)
                .build();

        manualMetadataRepository.save(metadata);
    }


    @Transactional
    public void deleteManual(Long manualId, Long companyId) {

        ManualMetadata manual = manualMetadataRepository.findById(manualId)
                .orElseThrow(() -> new NotFoundException("매뉴얼을 찾을 수 없습니다."));

        if (!manual.getCompany().getId().equals(companyId)) {
            throw new InvalidRequestException("해당 매뉴얼을 삭제할 권한이 없습니다.");
        }

        manual.deactivate();
        manual.updateStatus(STATUS_DELETE);

        // 벡터 삭제 실패 시에도 DB 는 DELETE(비활성)로 남긴다.
        // 챗봇 검색은 활성 매뉴얼의 file_id 만 허용하므로 남은 벡터가 답변에 쓰이지 않고,
        // DELETE 상태는 재활성화가 차단된다(updateManualActive). 실패는 반드시 로그로 남겨 정리할 수 있게 한다.
        String fileId = manual.getFile().getId().toString();
        try {
            chromaVectorService.deleteByFileId(companyId, fileId);
        } catch (Exception e) {
            log.error("[MANUAL_VECTOR_DELETE_FAILED] manualId={}, companyId={}, fileId={}",
                    manualId, companyId, fileId, e);
        }
    }


    @Transactional
    public void updateManualActive(Long companyId, Long manualId, boolean isActive) {
        ManualMetadata manual = manualRepository
                .findByIdAndCompanyId(manualId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("매뉴얼이 없거나 권한이 없습니다."));

        // 삭제된 매뉴얼은 검색 벡터가 제거되었거나(정상) 제거 실패로 남아 있을 수 있다(비정상).
        // 어느 쪽이든 재활성화하면 "성공/활성" 상태와 실제 검색 데이터가 어긋나므로 허용하지 않는다.
        if (STATUS_DELETE.equals(manual.getStatus())) {
            throw new InvalidRequestException("삭제된 매뉴얼은 다시 활성화할 수 없습니다. 파일을 다시 업로드해 주세요.");
        }

        if (isActive) {
            manual.activate();
        } else {
            manual.deactivate();
        }
    }
}