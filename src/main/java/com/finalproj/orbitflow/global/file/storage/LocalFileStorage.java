package com.finalproj.orbitflow.global.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@Profile("local")
@Slf4j
public class LocalFileStorage implements FileStorage {

    private final Path rootPath;

    public LocalFileStorage(
            @Value("${file.local.root-path:./uploads}")
            String rootPath
    ) {
        this.rootPath =
                Paths.get(rootPath)
                        .toAbsolutePath()
                        .normalize();

        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            throw new RuntimeException(
                    "로컬 파일 저장 디렉터리 생성 실패",
                    e
            );
        }

        log.info(
                "[LOCAL_FILE_STORAGE] rootPath={}",
                this.rootPath
        );
    }

    @Override
    public void save(
            String objectKey,
            String contentType,
            long size,
            InputStream inputStream
    ) {

        Path targetPath = resolvePath(objectKey);

        try {

            Path parent = targetPath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.copy(
                    inputStream,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
            );

            log.info(
                    "[LOCAL_FILE_SAVE] objectKey={}, path={}",
                    objectKey,
                    targetPath
            );

        } catch (IOException e) {

            throw new RuntimeException(
                    "로컬 파일 저장 실패. objectKey=" + objectKey,
                    e
            );
        }
    }

    @Override
    public Resource loadAsResource(String objectKey) {

        try {

            Path filePath = resolvePath(objectKey);

            if (!Files.exists(filePath)) {
                throw new RuntimeException(
                        "로컬 파일을 찾을 수 없습니다. objectKey="
                                + objectKey
                );
            }

            return new UrlResource(
                    filePath.toUri()
            );

        } catch (IOException e) {

            throw new RuntimeException(
                    "로컬 파일 Resource 로드 실패",
                    e
            );
        }
    }

    @Override
    public byte[] readAllBytes(String objectKey) {

        try {

            Path filePath = resolvePath(objectKey);

            return Files.readAllBytes(filePath);

        } catch (IOException e) {

            throw new RuntimeException(
                    "로컬 파일 읽기 실패. objectKey="
                            + objectKey,
                    e
            );
        }
    }

    @Override
    public void delete(String objectKey) {

        try {

            Path filePath = resolvePath(objectKey);

            Files.deleteIfExists(filePath);

            log.info(
                    "[LOCAL_FILE_DELETE] objectKey={}",
                    objectKey
            );

        } catch (IOException e) {

            log.error(
                    "[LOCAL_FILE_DELETE_FAIL] objectKey={}",
                    objectKey,
                    e
            );
        }
    }

    private Path resolvePath(String objectKey) {

        Path resolved =
                rootPath.resolve(objectKey)
                        .normalize();

        if (!resolved.startsWith(rootPath)) {
            throw new IllegalArgumentException(
                    "잘못된 objectKey입니다."
            );
        }

        return resolved;
    }
}