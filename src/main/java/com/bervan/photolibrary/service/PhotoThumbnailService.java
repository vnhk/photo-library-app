package com.bervan.photolibrary.service;

import com.bervan.logging.JsonLogger;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class PhotoThumbnailService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");

    public byte[] getOrCreateThumbnail(UUID photoId, File sourceFile, Path libraryRoot) {
        if (photoId == null || sourceFile == null || !sourceFile.exists()) {
            return null;
        }

        try {
            Path thumbDir = libraryRoot.resolve(".thumbnails");
            if (!Files.exists(thumbDir)) {
                Files.createDirectories(thumbDir);
            }
            Path thumbFile = thumbDir.resolve(photoId.toString() + ".jpg");

            // If thumbnail already exists on disk, read and return directly (no RAM retention, OS page cache handles speed)
            if (Files.exists(thumbFile) && Files.size(thumbFile) > 0) {
                return Files.readAllBytes(thumbFile);
            }

            // Generate thumbnail and persist to disk
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(sourceFile)
                    .size(420, 420)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(baos);

            byte[] generated = baos.toByteArray();
            Files.write(thumbFile, generated);
            return generated;
        } catch (Exception e) {
            log.warn("Failed to generate thumbnail for {}: {}", photoId, e.getMessage());
            return null;
        }
    }

    public void evict(UUID photoId, Path libraryRoot) {
        if (photoId == null || libraryRoot == null) return;
        try {
            Path thumbFile = libraryRoot.resolve(".thumbnails").resolve(photoId.toString() + ".jpg");
            Files.deleteIfExists(thumbFile);
        } catch (IOException ignored) {}
    }
}
