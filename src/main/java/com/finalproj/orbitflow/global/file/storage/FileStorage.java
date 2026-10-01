package com.finalproj.orbitflow.global.file.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

public interface FileStorage {

    void save(
            String objectKey,
            String contentType,
            long size,
            InputStream inputStream
    );

    Resource loadAsResource(String objectKey);

    byte[] readAllBytes(String objectKey);

    void delete(String objectKey);

    default String createPresignedDownloadUrl(
            String objectKey,
            String downloadFileName,
            long expirationMinutes
    ) {
        throw new UnsupportedOperationException(
                "현재 저장소에서는 Presigned URL을 지원하지 않습니다."
        );
    }
}