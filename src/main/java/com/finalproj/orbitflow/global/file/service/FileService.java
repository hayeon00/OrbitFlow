package com.finalproj.orbitflow.global.file.service;

import com.finalproj.orbitflow.global.exception.NotFoundException;
import com.finalproj.orbitflow.global.file.dto.PresignedUrlResDto;
import com.finalproj.orbitflow.global.file.entity.File;
import com.finalproj.orbitflow.global.file.enums.FileDomain;
import com.finalproj.orbitflow.global.file.repository.FileRepository;
import com.finalproj.orbitflow.global.file.storage.FileStorage;
import com.finalproj.orbitflow.hr.company.entity.Company;
import com.finalproj.orbitflow.hr.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * 파일 공통 서비스
 *
 * 실제 파일 저장 위치(S3 / Local)는 FileStorage 구현체가 담당한다.
 *
 * local profile -> LocalFileStorage
 * prod profile  -> S3FileStorage
 *
 * @author : Choi MinHyeok
 * @filename : FileService
 * @since : 26. 1. 1.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class FileService {

    private final FileRepository fileRepository;
    private final CompanyRepository companyRepository;

    /**
     * 실제 파일 저장소.
     *
     * local:
     * LocalFileStorage
     *
     * prod:
     * S3FileStorage
     */
    private final FileStorage fileStorage;


    /**
     * MultipartFile 업로드
     */
    @Transactional
    public File upload(
            Long companyId,
            FileDomain domain,
            MultipartFile multipartFile
    ) {

        Company company = companyRepository
                .findById(companyId)
                .orElseThrow(
                        () -> new NotFoundException(
                                "회사 정보를 찾을 수 없습니다. companyId="
                                        + companyId
                        )
                );

        String originFileName =
                Optional.ofNullable(
                                multipartFile.getOriginalFilename()
                        )
                        .filter(name -> !name.isBlank())
                        .orElse("unknown");

        try {

            return saveFileInternal(
                    company,
                    domain,
                    originFileName,
                    multipartFile.getContentType(),
                    multipartFile.getSize(),
                    multipartFile.getInputStream()
            );

        } catch (IOException e) {

            throw new RuntimeException(
                    "업로드 파일을 읽는 중 오류가 발생했습니다.",
                    e
            );
        }
    }


    /**
     * 시스템에서 생성한 PDF 저장
     */
    @Transactional
    public File saveGeneratedPdf(
            Long companyId,
            Long documentId,
            byte[] pdfBytes
    ) {

        Company company = companyRepository
                .findById(companyId)
                .orElseThrow(
                        () -> new NotFoundException(
                                "회사 정보를 찾을 수 없습니다. companyId="
                                        + companyId
                        )
                );

        String fileName =
                "document-" + documentId + ".pdf";

        return saveFileInternal(
                company,
                FileDomain.DOCUMENT_PDF,
                fileName,
                "application/pdf",
                pdfBytes.length,
                new ByteArrayInputStream(pdfBytes)
        );
    }


    /**
     * 실제 파일 저장 + DB 메타데이터 저장
     */
    private File saveFileInternal(
            Company company,
            FileDomain domain,
            String originFileName,
            String contentType,
            long size,
            InputStream inputStream
    ) {

        String objectKey =
                createObjectKey(
                        company.getId(),
                        domain,
                        originFileName
                );

        boolean storageSaved = false;

        try {

            /*
             * 1. 실제 파일 저장
             *
             * local:
             * ./uploads/...
             *
             * prod:
             * S3
             */
            fileStorage.save(
                    objectKey,
                    contentType,
                    size,
                    inputStream
            );

            storageSaved = true;

            /*
             * 2. DB 메타데이터 저장
             */
            File file = File.builder()
                    .company(company)
                    .domain(domain)
                    .objectKey(objectKey)
                    .originFile(originFileName)
                    .sysFile(
                            extractSysFile(objectKey)
                    )
                    .contentType(contentType)
                    .fileSize(size)
                    .build();

            File savedFile =
                    fileRepository.save(file);


            /*
             * 3. DB Transaction rollback 시
             * 이미 저장한 실제 파일 삭제
             */
            if (
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            ) {

                TransactionSynchronizationManager
                        .registerSynchronization(
                                new TransactionSynchronization() {

                                    @Override
                                    public void afterCompletion(
                                            int status
                                    ) {

                                        if (
                                                status
                                                        == STATUS_ROLLED_BACK
                                        ) {

                                            try {

                                                fileStorage.delete(
                                                        objectKey
                                                );

                                            } catch (Exception e) {

                                                log.error(
                                                        "[FILE_ROLLBACK_DELETE_FAIL] objectKey={}",
                                                        objectKey,
                                                        e
                                                );
                                            }
                                        }
                                    }
                                }
                        );
            }

            return savedFile;

        } catch (Exception e) {

            /*
             * 저장 중 실패했는데
             * 실제 파일까지 생성되어 있다면 삭제
             */
            if (storageSaved) {

                try {

                    fileStorage.delete(
                            objectKey
                    );

                } catch (Exception deleteException) {

                    log.error(
                            "[FILE_SAVE_COMPENSATION_DELETE_FAIL] objectKey={}",
                            objectKey,
                            deleteException
                    );
                }
            }

            log.error(
                    "[FILE_SAVE_FAIL] objectKey={}, originFileName={}",
                    objectKey,
                    originFileName,
                    e
            );

            throw new RuntimeException(
                    "파일 저장 중 오류 발생",
                    e
            );
        }
    }


    /**
     * 파일 다운로드
     */
    public ResponseEntity<Resource> download(
            Long fileId,
            Long companyId
    ) {

        File file = fileRepository
                .findByIdAndCompany_Id(
                        fileId,
                        companyId
                )
                .orElseThrow(
                        () -> new NotFoundException(
                                "파일을 찾을 수 없습니다. id="
                                        + fileId
                        )
                );

        Resource resource =
                fileStorage.loadAsResource(
                        file.getObjectKey()
                );

        MediaType mediaType =
                MediaType.APPLICATION_OCTET_STREAM;

        if (
                file.getContentType() != null
                        && !file.getContentType().isBlank()
        ) {

            try {

                mediaType =
                        MediaType.parseMediaType(
                                file.getContentType()
                        );

            } catch (Exception e) {

                log.warn(
                        "[INVALID_CONTENT_TYPE] fileId={}, contentType={}",
                        file.getId(),
                        file.getContentType()
                );
            }
        }

        String encodedName =
                UriUtils.encode(
                        Optional.ofNullable(
                                        file.getOriginFile()
                                )
                                .orElse("download"),
                        StandardCharsets.UTF_8
                );

        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok()
                        .contentType(mediaType)
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\""
                                        + encodedName
                                        + "\""
                        );

        if (
                file.getFileSize() != null
                        && file.getFileSize() >= 0
        ) {

            response.contentLength(
                    file.getFileSize()
            );
        }

        return response.body(resource);
    }


    /**
     * Presigned URL 생성
     *
     * S3 환경에서는 실제 Presigned URL 생성.
     *
     * LocalFileStorage에서는 구현 방식에 따라
     * UnsupportedOperationException이 발생할 수 있다.
     */
    public PresignedUrlResDto createPresignedDownloadUrl(
            Long companyId,
            Long fileId
    ) {

        File file = fileRepository
                .findByIdAndCompany_Id(
                        fileId,
                        companyId
                )
                .orElseThrow(
                        () -> new NotFoundException(
                                "파일을 찾을 수 없습니다. id="
                                        + fileId
                        )
                );

        long expirationMinutes = 5L;

        String url =
                fileStorage.createPresignedDownloadUrl(
                        file.getObjectKey(),
                        Optional.ofNullable(
                                        file.getOriginFile()
                                )
                                .orElse("download"),
                        expirationMinutes
                );

        return new PresignedUrlResDto(
                url,
                LocalDateTime.now()
                        .plusMinutes(
                                expirationMinutes
                        )
        );
    }


    /**
     * 실제 파일 삭제
     */
    public void deleteObject(
            String objectKey
    ) {

        try {

            fileStorage.delete(
                    objectKey
            );

        } catch (Exception ex) {

            log.error(
                    "[FILE_DELETE_FAIL] objectKey={}",
                    objectKey,
                    ex
            );
        }
    }


    /**
     * 현재 Transaction commit 이후
     * 실제 저장소 파일 삭제
     */
    public void deleteObjectAfterCommit(
            String objectKey
    ) {

        if (
                !TransactionSynchronizationManager
                        .isActualTransactionActive()
        ) {

            deleteObject(
                    objectKey
            );

            return;
        }

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {

                                try {

                                    deleteObject(
                                            objectKey
                                    );

                                } catch (Exception e) {

                                    log.error(
                                            "[FILE_DELETE_AFTER_COMMIT_FAIL] objectKey={}",
                                            objectKey,
                                            e
                                    );
                                }
                            }
                        }
                );
    }


    /**
     * 이미지 바이트 반환
     */
    public ResponseEntity<byte[]> streamImage(
            File file
    ) {

        byte[] bytes =
                fileStorage.readAllBytes(
                        file.getObjectKey()
                );

        MediaType mediaType =
                MediaType.APPLICATION_OCTET_STREAM;

        if (
                file.getContentType() != null
                        && !file.getContentType().isBlank()
        ) {

            try {

                mediaType =
                        MediaType.parseMediaType(
                                file.getContentType()
                        );

            } catch (Exception e) {

                log.warn(
                        "[INVALID_IMAGE_CONTENT_TYPE] fileId={}, contentType={}",
                        file.getId(),
                        file.getContentType()
                );
            }
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(bytes);
    }


    /**
     * 내부용 Resource 로드
     */
    public Resource loadAsResource(
            File file
    ) {

        return fileStorage.loadAsResource(
                file.getObjectKey()
        );
    }


    /**
     * 저장 Object Key 생성
     *
     * 예:
     *
     * company/1/chat/2026/10/
     * uuid_manual.pdf
     */
    private String createObjectKey(
            Long companyId,
            FileDomain domain,
            String originFileName
    ) {

        String uuid =
                UUID.randomUUID()
                        .toString();

        String datePath =
                LocalDate.now()
                        .format(
                                DateTimeFormatter.ofPattern(
                                        "yyyy/MM"
                                )
                        );

        /*
         * 파일명 안의 경로 문자 제거.
         *
         * 클라이언트가 이상한 파일명을 보내도
         * objectKey 구조가 깨지지 않도록 한다.
         */
        String safeFileName =
                sanitizeFileName(
                        originFileName
                );

        return "company/%d/%s/%s/%s_%s"
                .formatted(
                        companyId,
                        domain.getPath(),
                        datePath,
                        uuid,
                        safeFileName
                );
    }


    /**
     * objectKey 마지막 파일명 추출
     */
    private String extractSysFile(
            String objectKey
    ) {

        return objectKey.substring(
                objectKey.lastIndexOf('/')
                        + 1
        );
    }


    /**
     * 파일명에 포함된 경로 문자 제거
     */
    private String sanitizeFileName(
            String fileName
    ) {

        return fileName
                .replace("\\", "_")
                .replace("/", "_");
    }
}