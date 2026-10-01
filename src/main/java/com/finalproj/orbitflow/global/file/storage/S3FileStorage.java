package com.finalproj.orbitflow.global.file.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@Profile("prod")
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Override
    public void save(
            String objectKey,
            String contentType,
            long size,
            InputStream inputStream
    ) {

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(
                        inputStream,
                        size
                )
        );
    }

    @Override
    public Resource loadAsResource(String objectKey) {

        ResponseInputStream<GetObjectResponse> s3Object =
                s3Client.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(objectKey)
                                .build()
                );

        return new InputStreamResource(s3Object);
    }

    @Override
    public byte[] readAllBytes(String objectKey) {

        try (
                ResponseInputStream<GetObjectResponse> s3Object =
                        s3Client.getObject(
                                GetObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(objectKey)
                                        .build()
                        )
        ) {

            return s3Object.readAllBytes();

        } catch (IOException e) {

            throw new RuntimeException(
                    "S3 파일 읽기 실패",
                    e
            );
        }
    }

    @Override
    public void delete(String objectKey) {

        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()
        );
    }

    @Override
    public String createPresignedDownloadUrl(
            String objectKey,
            String downloadFileName,
            long expirationMinutes
    ) {

        GetObjectRequest request =
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .responseContentDisposition(
                                "attachment; filename=\""
                                        + UriUtils.encode(
                                        downloadFileName,
                                        StandardCharsets.UTF_8
                                )
                                        + "\""
                        )
                        .build();

        PresignedGetObjectRequest presignedRequest =
                s3Presigner.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(
                                        Duration.ofMinutes(
                                                expirationMinutes
                                        )
                                )
                                .getObjectRequest(request)
                                .build()
                );

        return presignedRequest
                .url()
                .toString();
    }
}