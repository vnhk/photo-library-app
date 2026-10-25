package com.bervan.photolibrary.service;

import com.bervan.logging.JsonLogger;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PhotoThumbnailService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");
    private final Map<UUID, byte[]> memoryCache = new ConcurrentHashMap<>();

    public byte[] getOrCreateThumbnail(UUID photoId, File sourceFile, Path libraryRoot) {
        if (photoId == null || sourceFile == null || !sourceFile.exists()) {
            return null;
        }

        byte[] cached = memoryCache.get(photoId);
        if (cached != null) {
            return cached;
        }

        try {
            Path thumbDir = libraryRoot.resolve(".thumbnails");
            if (!Files.exists(thumbDir)) {
                Files.createDirectories(thumbDir);
            }
            Path thumbFile = thumbDir.resolve(photoId.toString() + ".jpg");

            if (Files.exists(thumbFile) && Files.size(thumbFile) > 0) {
                byte[] bytes = Files.readAllBytes(thumbFile);
                memoryCache.put(photoId, bytes);
                return bytes;
            }

            // Generate thumbnail
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(sourceFile)
                    .size(420, 420)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(baos);

            byte[] generated = baos.toByteArray();
            Files.write(thumbFile, generated);
            memoryCache.put(photoId, generated);
            return generated;
        } catch (Exception e) {
            log.warn("Failed to generate thumbnail for {}: {}", photoId, e.getMessage());
            return null;
        }
    }

    public void evict(UUID photoId, Path libraryRoot) {
        if (photoId == null) return;
        memoryCache.remove(photoId);
        try {
            if (libraryRoot != null) {
                Path thumbFile = libraryRoot.resolve(".thumbnails").resolve(photoId.toString() + ".jpg");
                Files.deleteIfExists(thumbFile);
            }
        } catch (IOException ignored) {}
    }

    public void clearMemory() {
        memoryCache.clear();
    }
}
