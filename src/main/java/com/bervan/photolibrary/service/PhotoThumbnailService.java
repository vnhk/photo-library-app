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

    private static final java.util.Set<String> VIDEO_EXTENSIONS = java.util.Set.of(
            "mov", "mp4", "webm", "mkv", "avi", "m4v"
    );

    private static final java.util.Set<String> HEIC_EXTENSIONS = java.util.Set.of(
            "heic", "heif", "hif"
    );

    private boolean isVideoFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        int idx = name.lastIndexOf('.');
        if (idx < 0) return false;
        return VIDEO_EXTENSIONS.contains(name.substring(idx + 1));
    }

    private boolean isHeicFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        int idx = name.lastIndexOf('.');
        if (idx < 0) return false;
        return HEIC_EXTENSIONS.contains(name.substring(idx + 1));
    }

    private byte[] tryGenerateVideoThumbnailWithFfmpeg(File videoFile) {
        try {
            Process process = new ProcessBuilder(
                    "ffmpeg", "-ss", "00:00:00.500", "-i", videoFile.getAbsolutePath(),
                    "-vf", "scale=420:420:force_original_aspect_ratio=decrease",
                    "-vframes", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "-"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            try (var is = process.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                int exitCode = process.waitFor();
                if (exitCode == 0 && bytes.length > 0) {
                    log.info("Extracted video thumbnail via ffmpeg for: {}", videoFile.getName());
                    return bytes;
                }
            }
        } catch (Exception ignored) {
            // ffmpeg not installed or not in PATH, will fall back to client-side capture
        }
        return null;
    }

    private byte[] tryGenerateImageThumbnailWithFfmpeg(File imageFile) {
        try {
            Process process = new ProcessBuilder(
                    "ffmpeg", "-i", imageFile.getAbsolutePath(),
                    "-vf", "scale=420:420:force_original_aspect_ratio=decrease",
                    "-vframes", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "3", "-"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            try (var is = process.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                int exitCode = process.waitFor();
                if (exitCode == 0 && bytes.length > 0) {
                    log.info("Extracted image thumbnail via ffmpeg for: {}", imageFile.getName());
                    return bytes;
                }
            }
        } catch (Exception ignored) {
            // ffmpeg not installed or not in PATH, will fall back
        }
        return null;
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

            if (isVideoFile(sourceFile)) {
                byte[] ffmpegThumb = tryGenerateVideoThumbnailWithFfmpeg(sourceFile);
                if (ffmpegThumb != null && ffmpegThumb.length > 0) {
                    saveOrUpdateThumbnail(photoId, ffmpegThumb);
                    return ffmpegThumb;
                }
                return null;
            }

            if (isHeicFile(sourceFile)) {
                byte[] heicThumb = tryGenerateImageThumbnailWithFfmpeg(sourceFile);
                if (heicThumb != null && heicThumb.length > 0) {
                    saveOrUpdateThumbnail(photoId, heicThumb);
                    return heicThumb;
                }
                return null;
            }

            try {
                // Generate thumbnail using Thumbnailator for standard images
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
                // Fallback to ffmpeg for other image types not supported by ImageIO (e.g. webp, tiff)
                byte[] ffmpegFallback = tryGenerateImageThumbnailWithFfmpeg(sourceFile);
                if (ffmpegFallback != null && ffmpegFallback.length > 0) {
                    saveOrUpdateThumbnail(photoId, ffmpegFallback);
                    return ffmpegFallback;
                }
                log.warn("Failed to generate thumbnail for {}: {}", photoId, e.getMessage());
                return null;
            }
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
