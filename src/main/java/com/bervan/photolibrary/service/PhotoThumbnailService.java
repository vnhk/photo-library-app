package com.bervan.photolibrary.service;

import com.bervan.filestorage.model.BervanMockMultiPartFile;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileDBStorageService;
import com.bervan.filestorage.service.FileDiskStorageService;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.logging.JsonLogger;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PhotoThumbnailService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");

    private final FileServiceManager fileServiceManager;
    private final FileDiskStorageService fileDiskStorageService;
    private final FileDBStorageService fileDBStorageService;

    @Value("${photo-library.file-storage-relative-path:/Photo Library}")
    private String photoRelativePath;

    public PhotoThumbnailService(FileServiceManager fileServiceManager,
                                 FileDiskStorageService fileDiskStorageService,
                                 FileDBStorageService fileDBStorageService) {
        this.fileServiceManager = fileServiceManager;
        this.fileDiskStorageService = fileDiskStorageService;
        this.fileDBStorageService = fileDBStorageService;
    }

    private String getNormalizedPhotoPath() {
        String path = (photoRelativePath != null && !photoRelativePath.isBlank() && !photoRelativePath.startsWith("${"))
                ? photoRelativePath.trim()
                : "/Photo Library";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String getThumbnailParentPath() {
        return getNormalizedPhotoPath() + "/";
    }

    private String getThumbnailStoragePath() {
        return getNormalizedPhotoPath() + "/.thumbnails/";
    }

    private synchronized void ensureThumbnailDirectory() {
        String parentPath = getThumbnailParentPath();
        String dirName = ".thumbnails";
        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(parentPath, dirName);
            if (found.isEmpty()) {
                fileServiceManager.createEmptyDirectory(parentPath, dirName);
                log.info("Created .thumbnails directory in {}{}", parentPath, dirName);
            }
        } catch (Exception e) {
            log.warn("Could not ensure thumbnail directory {}{}: {}", parentPath, dirName, e.getMessage());
        }
    }

    public byte[] getOrCreateThumbnail(UUID photoId, File sourceFile) {
        if (photoId == null || sourceFile == null || !sourceFile.exists()) {
            return null;
        }

        String storagePath = getThumbnailStoragePath();
        String filename = photoId.toString() + ".jpg";

        try {
            // Check if thumbnail metadata exists in database
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(storagePath, filename);
            if (!found.isEmpty()) {
                Metadata meta = found.get(0);
                Path thumbPath = fileDiskStorageService.getFile(meta.getPath() + meta.getFilename());
                if (Files.exists(thumbPath) && Files.size(thumbPath) > 0) {
                    return Files.readAllBytes(thumbPath);
                }
            } else {
                // If metadata is not in DB but physical file exists, register and return
                Path thumbPath = fileDiskStorageService.getFile(storagePath + filename);
                if (Files.exists(thumbPath) && Files.size(thumbPath) > 0) {
                    byte[] bytes = Files.readAllBytes(thumbPath);
                    saveOrUpdateThumbnail(photoId, bytes);
                    return bytes;
                }
            }

            // Generate thumbnail using Thumbnailator
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(sourceFile)
                    .size(420, 420)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(baos);

            byte[] generated = baos.toByteArray();
            saveOrUpdateThumbnail(photoId, generated);
            return generated;
        } catch (Exception e) {
            log.warn("Failed to generate thumbnail for {}: {}", photoId, e.getMessage());
            return null;
        }
    }

    public byte[] getOrCreateThumbnail(UUID photoId, File sourceFile, Path ignoredLibraryRoot) {
        return getOrCreateThumbnail(photoId, sourceFile);
    }

    public void saveThumbnail(UUID photoId, byte[] thumbnailBytes) {
        saveOrUpdateThumbnail(photoId, thumbnailBytes);
    }

    public void saveThumbnail(UUID photoId, byte[] thumbnailBytes, Path ignoredLibraryRoot) {
        saveThumbnail(photoId, thumbnailBytes);
    }

    private void saveOrUpdateThumbnail(UUID photoId, byte[] thumbnailBytes) {
        if (photoId == null || thumbnailBytes == null || thumbnailBytes.length == 0) return;
        ensureThumbnailDirectory();
        String storagePath = getThumbnailStoragePath();
        String filename = photoId.toString() + ".jpg";

        Metadata existingMeta = null;
        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(storagePath, filename);
            if (!found.isEmpty()) {
                existingMeta = found.get(0);
            }
        } catch (Exception e) {
            log.warn("Could not check existing metadata for {} in {}: {}", filename, storagePath, e.getMessage());
        }

        if (existingMeta != null) {
            Path targetFile = fileDiskStorageService.getFile(existingMeta.getPath() + existingMeta.getFilename());
            fileDiskStorageService.overwriteFile(targetFile, thumbnailBytes);
            existingMeta.setFileSize((long) thumbnailBytes.length);
            existingMeta.setModificationDate(LocalDateTime.now());
            fileServiceManager.updateMetadata(existingMeta);
            log.info("Overwrote thumbnail for: {}", photoId);
        } else {
            // Prevent duplicate files (e.g. filename(1).jpg) if stray physical file existed
            try {
                Path targetFile = fileDiskStorageService.getFile(storagePath + filename);
                Files.deleteIfExists(targetFile);
            } catch (Exception ignored) {}

            BervanMockMultiPartFile mockFile = new BervanMockMultiPartFile(
                    filename, filename, "image/jpeg", new ByteArrayInputStream(thumbnailBytes)
            );
            fileServiceManager.save(mockFile, "Photo thumbnail", storagePath);
            log.info("Saved thumbnail via FileServiceManager for: {}", photoId);
        }
    }

    public void evict(UUID photoId) {
        if (photoId == null) return;
        String storagePath = getThumbnailStoragePath();
        String filename = photoId.toString() + ".jpg";
        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(storagePath, filename);
            for (Metadata m : found) {
                fileServiceManager.delete(m);
            }
        } catch (Exception e) {
            log.warn("Failed to evict thumbnail for {}: {}", photoId, e.getMessage());
        }
    }

    public void evict(UUID photoId, Path ignoredLibraryRoot) {
        evict(photoId);
    }
}
