package com.bervan.photolibrary.controller;

import com.bervan.logging.JsonLogger;
import com.bervan.photolibrary.model.MediaType;
import com.bervan.photolibrary.model.PhotoAlbum;
import com.bervan.photolibrary.model.PhotoItem;
import com.bervan.photolibrary.service.PhotoStorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/photos")
public class PhotoLibraryApiController {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");
    private final PhotoStorageService photoStorageService;

    public PhotoLibraryApiController(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    @GetMapping
    public ResponseEntity<List<PhotoItem>> getPhotos(
            @RequestParam(required = false) String albumId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) MediaType mediaType,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(photoStorageService.getPhotos(albumId, tag, mediaType, favorite, query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PhotoItem> getPhotoById(@PathVariable UUID id) {
        PhotoItem item = photoStorageService.getPhotoById(id);
        if (item == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(item);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<?> getFile(
            @PathVariable UUID id,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @RequestParam(value = "download", required = false) Boolean download
    ) throws IOException {
        File file = photoStorageService.getPhysicalFile(id);
        if (file == null || !file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        long contentLength = file.length();
        String filename = file.getName().toLowerCase();
        org.springframework.http.MediaType mediaType = resolveMediaType(filename);

        // Byte-range streaming for video / large media
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (!ranges.isEmpty()) {
                    HttpRange range = ranges.get(0);
                    long start = range.getRangeStart(contentLength);
                    long end = range.getRangeEnd(contentLength);
                    long rangeLength = Math.min(1024 * 1024 * 2L, end - start + 1); // 2MB chunk
                    ResourceRegion region = new ResourceRegion(resource, start, rangeLength);

                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                            .contentType(mediaType)
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + (start + rangeLength - 1) + "/" + contentLength)
                            .body(region);
                }
            } catch (Exception e) {
                log.warn("Could not parse Range header {}: {}", rangeHeader, e.getMessage());
            }
        }

        var responseBuilder = ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400, public");

        if (Boolean.TRUE.equals(download)) {
            responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"");
        }

        return responseBuilder.body(resource);
    }

    @PostMapping(value = "/download-zip")
    public void downloadZip(@RequestBody List<UUID> ids, HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"photos_" + System.currentTimeMillis() + ".zip\"");
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            Set<String> usedNames = new HashSet<>();
            if (ids != null) {
                for (UUID id : ids) {
                    File file = photoStorageService.getPhysicalFile(id);
                    if (file != null && file.exists()) {
                        String entryName = file.getName();
                        int counter = 1;
                        while (usedNames.contains(entryName)) {
                            entryName = counter + "_" + file.getName();
                            counter++;
                        }
                        usedNames.add(entryName);
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file.toPath(), zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    @GetMapping(value = "/albums/{id}/download-zip")
    public void downloadAlbumZip(@PathVariable String id, HttpServletResponse response) throws IOException {
        PhotoAlbum album = photoStorageService.getAlbumById(id);
        if (album == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        List<PhotoItem> items = photoStorageService.getPhotos(id, null, null, null, null);
        String safeName = (album.getName() != null ? album.getName() : "album").replaceAll("[^a-zA-Z0-9._-]", "_");
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + ".zip\"");
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            Set<String> usedNames = new HashSet<>();
            for (PhotoItem item : items) {
                File file = photoStorageService.getPhysicalFile(item.getId());
                if (file != null && file.exists()) {
                    String entryName = file.getName();
                    int counter = 1;
                    while (usedNames.contains(entryName)) {
                        entryName = counter + "_" + file.getName();
                        counter++;
                    }
                    usedNames.add(entryName);
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file.toPath(), zos);
                    zos.closeEntry();
                }
            }
        }
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable UUID id) {
        byte[] thumb = photoStorageService.getThumbnail(id);
        if (thumb == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=604800, public")
                .body(thumb);
    }

    @PostMapping("/upload")
    public ResponseEntity<PhotoItem> uploadPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam(value = "tags", required = false) List<String> tags
    ) {
        try {
            Set<String> tagSet = (tags != null) ? new HashSet<>(tags) : new HashSet<>();
            PhotoItem created = photoStorageService.uploadPhoto(file, folder, tagSet);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("Failed to upload photo", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{id}/tags")
    public ResponseEntity<PhotoItem> updateTags(
            @PathVariable UUID id,
            @RequestBody Set<String> tags
    ) {
        PhotoItem updated = photoStorageService.updateTags(id, tags);
        if (updated == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(updated);
    }

    public record BatchTagsRequest(
            Set<UUID> ids,
            Set<String> addTags,
            Set<String> removeTags
    ) {}

    @PostMapping("/batch-tags")
    public ResponseEntity<Void> batchUpdateTags(@RequestBody BatchTagsRequest request) {
        if (request.ids() != null && !request.ids().isEmpty()) {
            photoStorageService.batchUpdateTags(request.ids(), request.addTags(), request.removeTags());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/favorite")
    public ResponseEntity<PhotoItem> toggleFavorite(@PathVariable UUID id) {
        PhotoItem updated = photoStorageService.toggleFavorite(id);
        if (updated == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePhoto(@PathVariable UUID id) {
        photoStorageService.deletePhoto(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/albums")
    public ResponseEntity<List<PhotoAlbum>> getAlbums() {
        return ResponseEntity.ok(photoStorageService.getAlbums());
    }

    @GetMapping("/albums/{id}")
    public ResponseEntity<PhotoAlbum> getAlbumById(@PathVariable String id) {
        PhotoAlbum album = photoStorageService.getAlbumById(id);
        if (album == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(album);
    }

    @PostMapping("/albums")
    public ResponseEntity<PhotoAlbum> saveAlbum(@RequestBody PhotoAlbum album) {
        return ResponseEntity.ok(photoStorageService.saveAlbum(album));
    }

    @DeleteMapping("/albums/{id}")
    public ResponseEntity<Void> deleteAlbum(@PathVariable String id) {
        photoStorageService.deleteAlbum(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tags")
    public ResponseEntity<Map<String, Long>> getTags() {
        return ResponseEntity.ok(photoStorageService.getTagCounts());
    }

    @PostMapping("/reload")
    public ResponseEntity<Void> reloadLibrary() {
        photoStorageService.reloadLibrary();
        return ResponseEntity.ok().build();
    }

    private org.springframework.http.MediaType resolveMediaType(String filename) {
        if (filename == null) return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        String fn = filename.toLowerCase();
        if (fn.endsWith(".jpg") || fn.endsWith(".jpeg")) return org.springframework.http.MediaType.IMAGE_JPEG;
        if (fn.endsWith(".png")) return org.springframework.http.MediaType.IMAGE_PNG;
        if (fn.endsWith(".webp")) return org.springframework.http.MediaType.parseMediaType("image/webp");
        if (fn.endsWith(".heic") || fn.endsWith(".heif")) return org.springframework.http.MediaType.parseMediaType("image/heic");
        if (fn.endsWith(".gif")) return org.springframework.http.MediaType.IMAGE_GIF;
        if (fn.endsWith(".mp4")) return org.springframework.http.MediaType.parseMediaType("video/mp4");
        if (fn.endsWith(".webm")) return org.springframework.http.MediaType.parseMediaType("video/webm");
        if (fn.endsWith(".mov")) return org.springframework.http.MediaType.parseMediaType("video/quicktime");
        if (fn.endsWith(".mkv")) return org.springframework.http.MediaType.parseMediaType("video/x-matroska");
        return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
    }
}
