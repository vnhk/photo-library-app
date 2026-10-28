package com.bervan.photolibrary.service;

import com.bervan.filestorage.model.BervanMockMultiPartFile;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileDBStorageService;
import com.bervan.filestorage.service.FileDiskStorageService;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.logging.JsonLogger;
import com.bervan.photolibrary.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PhotoStorageService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");

    private final FileServiceManager fileServiceManager;
    private final FileDiskStorageService fileDiskStorageService;
    private final FileDBStorageService fileDBStorageService;
    private final PhotoThumbnailService thumbnailService;
    private final PhotoMetadataExtractorService extractorService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${photo-library.file-storage-relative-path:/Photo Library}")
    private String photoRelativePath;

    private final Map<UUID, PhotoItem> itemsById = new ConcurrentHashMap<>();
    private final Map<String, PhotoAlbum> albumsById = new ConcurrentHashMap<>();
    private PhotoLibraryData libraryData = new PhotoLibraryData();

    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "tiff"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "webm", "mkv", "avi", "m4v"
    );

    public PhotoStorageService(FileServiceManager fileServiceManager,
                               FileDiskStorageService fileDiskStorageService,
                               FileDBStorageService fileDBStorageService,
                               PhotoThumbnailService thumbnailService,
                               PhotoMetadataExtractorService extractorService) {
        this.fileServiceManager = fileServiceManager;
        this.fileDiskStorageService = fileDiskStorageService;
        this.fileDBStorageService = fileDBStorageService;
        this.thumbnailService = thumbnailService;
        this.extractorService = extractorService;
    }

    @PostConstruct
    public void init() {
        try {
            reloadLibrary();
        } catch (Exception e) {
            log.error("Failed to initialize photo library", e);
        }
    }

    public String getPhotoRelativePath() {
        String path = (photoRelativePath != null && !photoRelativePath.isBlank() && !photoRelativePath.startsWith("${"))
                ? photoRelativePath.trim()
                : "/Photo Library";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public Path getPhotoLibraryRoot() {
        return fileDiskStorageService.getFile(getPhotoRelativePath());
    }

    private String ensureStoragePathEndsWithSlash(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.endsWith("/") ? path : path + "/";
    }

    public synchronized void reloadLibrary() {
        log.info("Starting Photo Library reload from: {}", getPhotoRelativePath());
        Path root = getPhotoLibraryRoot();
        String rootDirName = root.getFileName().toString();

        String rootStoragePath = ensureStoragePathEndsWithSlash(getPhotoRelativePath());
        try {
            List<Metadata> existing = fileDBStorageService.loadByPathAndFilename("/", rootDirName);
            boolean onDisk = Files.exists(root);

            if (existing.isEmpty()) {
                if (!onDisk) {
                    fileServiceManager.createEmptyDirectory("/", rootDirName);
                } else {
                    fileDBStorageService.createEmptyDirectory("/", rootDirName);
                }
            } else if (!onDisk) {
                fileDiskStorageService.createEmptyDirectory("/", rootDirName);
            }
        } catch (Exception e) {
            log.warn("Could not ensure root directory {}: {}", rootDirName, e.getMessage());
        }

        loadLibraryData();

        itemsById.clear();
        scanDirectoryRecursively(root, rootStoragePath);

        // Populate album items
        refreshAlbums();
        log.info("Photo Library reload finished. Indexed {} media items and {} albums.",
                itemsById.size(), albumsById.size());
    }

    private void scanDirectoryRecursively(Path dir, String currentStoragePath) {
        if (!Files.exists(dir)) return;

        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.collect(Collectors.toList());
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue; // Skip hidden dirs/files (.thumbnails, etc.)

                if (Files.isDirectory(entry)) {
                    String subStoragePath = ensureStoragePathEndsWithSlash(currentStoragePath + name);
                    ensureDirectoryMetadata(currentStoragePath, name);
                    scanDirectoryRecursively(entry, subStoragePath);
                } else if (Files.isRegularFile(entry)) {
                    if ("library-data.json".equalsIgnoreCase(name)) continue;
                    indexFile(entry, currentStoragePath);
                }
            }
        } catch (Exception e) {
            log.error("Error scanning directory: " + dir, e);
        }
    }

    private void indexFile(Path file, String storagePath) {
        String filename = file.getFileName().toString();
        String ext = getExtension(filename).toLowerCase();

        MediaType mediaType = null;
        if (PHOTO_EXTENSIONS.contains(ext)) {
            mediaType = MediaType.PHOTO;
        } else if (VIDEO_EXTENSIONS.contains(ext)) {
            mediaType = MediaType.VIDEO;
        }
        if (mediaType == null) return;

        Metadata meta = ensureFileMetadata(storagePath, filename, ext, file);
        if (meta == null || meta.getId() == null) return;

        UUID id = meta.getId();
        PhotoCustomMetadata custom = libraryData.getItemMetadata().getOrDefault(id.toString(), new PhotoCustomMetadata());

        // Extract metadata if needed
        LocalDateTime dateTaken = custom.getCustomDateTaken();
        Integer width = custom.getWidth();
        Integer height = custom.getHeight();
        String camera = custom.getCameraModel();
        Double duration = custom.getDurationSeconds();

        if (dateTaken == null || (mediaType == MediaType.PHOTO && width == null)) {
            var extracted = extractorService.extract(file.toFile(), mediaType);
            if (dateTaken == null) {
                dateTaken = extracted.dateTaken() != null ? extracted.dateTaken() : meta.getCreateDate();
                if (dateTaken == null) dateTaken = LocalDateTime.now();
            }
            if (width == null) width = extracted.width();
            if (height == null) height = extracted.height();
            if (camera == null) camera = extracted.cameraModel();
            if (duration == null) duration = extracted.durationSeconds();
        }

        Set<String> tags = new HashSet<>(custom.getTags());
        // Derive tag from parent folder name if not root
        String parentFolderName = file.getParent().getFileName().toString();
        if (!parentFolderName.equalsIgnoreCase(getPhotoLibraryRoot().getFileName().toString())) {
            tags.add(parentFolderName.trim());
        }

        PhotoItem item = PhotoItem.builder()
                .id(id)
                .filename(filename)
                .path(storagePath)
                .extension(ext)
                .mediaType(mediaType)
                .fileSize(meta.getFileSize() != null ? meta.getFileSize() : 0L)
                .width(width)
                .height(height)
                .dateTaken(dateTaken)
                .tags(tags)
                .favorite(Boolean.TRUE.equals(custom.getFavorite()))
                .description(custom.getDescription())
                .durationSeconds(duration)
                .cameraModel(camera)
                .build();

        itemsById.put(id, item);
    }

    private Metadata ensureFileMetadata(String storagePath, String filename, String ext, Path file) {
        String cleanPath = ensureStoragePathEndsWithSlash(storagePath);
        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(cleanPath, filename);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            long size = Files.size(file);
            Metadata m = new Metadata(UUID.randomUUID(), cleanPath, filename, ext, false);
            m.setFileSize(size);
            m.setCreateDate(LocalDateTime.now());
            m.setModificationDate(LocalDateTime.now());
            return fileDBStorageService.store(m);
        } catch (Exception e) {
            log.warn("Could not check/create metadata for {}{}: {}", cleanPath, filename, e.getMessage());
            return null;
        }
    }

    private void ensureDirectoryMetadata(String parentStoragePath, String dirName) {
        try {
            String cleanParent = ensureStoragePathEndsWithSlash(parentStoragePath);
            List<Metadata> existing = fileDBStorageService.loadByPathAndFilename(cleanParent, dirName);
            if (existing.isEmpty()) {
                fileDBStorageService.createEmptyDirectory(cleanParent, dirName);
            }
        } catch (Exception e) {
            log.warn("Could not ensure directory metadata for {}{}: {}", parentStoragePath, dirName, e.getMessage());
        }
    }

    private void loadLibraryData() {
        albumsById.clear();
        try {
            Path file = getPhotoLibraryRoot().resolve("library-data.json");
            if (Files.exists(file)) {
                this.libraryData = objectMapper.readValue(file.toFile(), PhotoLibraryData.class);
                if (libraryData.getAlbums() != null) {
                    for (PhotoAlbum a : libraryData.getAlbums()) {
                        albumsById.put(a.getId(), a);
                    }
                }
            } else {
                this.libraryData = new PhotoLibraryData();
            }
        } catch (Exception e) {
            log.error("Failed to load library-data.json, initializing empty: {}", e.getMessage());
            this.libraryData = new PhotoLibraryData();
        }
    }

    public synchronized void saveLibraryData() {
        try {
            this.libraryData.setAlbums(new ArrayList<>(albumsById.values()));
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(this.libraryData);
            saveOrUpdateFile("library-data.json", content, "application/json", "Photo Library Config",
                    getPhotoRelativePath(), getPhotoLibraryRoot());
        } catch (Exception e) {
            log.error("Failed to persist library-data.json", e);
        }
    }

    private void saveOrUpdateFile(String filename, byte[] content, String contentType,
                                  String description, String storagePath, Path dir) {
        String cleanStoragePath = ensureStoragePathEndsWithSlash(storagePath);
        Metadata existingMeta = null;
        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(cleanStoragePath, filename);
            if (!found.isEmpty()) existingMeta = found.get(0);
        } catch (Exception ignored) {}

        if (existingMeta != null) {
            Path targetFile = dir.resolve(filename);
            fileDiskStorageService.overwriteFile(targetFile, content);
            existingMeta.setFileSize((long) content.length);
            existingMeta.setModificationDate(LocalDateTime.now());
            fileServiceManager.updateMetadata(existingMeta);
        } else {
            try {
                Files.deleteIfExists(dir.resolve(filename));
            } catch (Exception ignored) {}
            BervanMockMultiPartFile mockFile = new BervanMockMultiPartFile(
                    filename, filename, contentType, new ByteArrayInputStream(content)
            );
            fileServiceManager.save(mockFile, description, cleanStoragePath);
        }
    }

    private void refreshAlbums() {
        for (PhotoAlbum album : albumsById.values()) {
            int count = 0;
            UUID coverId = album.getCoverPhotoId();
            for (PhotoItem item : itemsById.values()) {
                if (isItemInAlbum(item, album)) {
                    count++;
                    if (coverId == null && item.getMediaType() == MediaType.PHOTO) {
                        coverId = item.getId();
                    }
                }
            }
            album.setPhotoCount(count);
            if (album.getCoverPhotoId() == null) {
                album.setCoverPhotoId(coverId);
            }
        }
    }

    private boolean isItemInAlbum(PhotoItem item, PhotoAlbum album) {
        if (album.getPhotoIds() != null && album.getPhotoIds().contains(item.getId())) {
            return true;
        }
        if (album.getTags() != null && !album.getTags().isEmpty() && item.getTags() != null) {
            for (String t : album.getTags()) {
                if (item.getTags().stream().anyMatch(it -> it.equalsIgnoreCase(t))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Public API query methods

    public List<PhotoItem> getPhotos(String albumId, String tag, MediaType mediaType, Boolean favorite, String query) {
        PhotoAlbum album = (albumId != null && !albumId.isBlank()) ? albumsById.get(albumId) : null;

        return itemsById.values().stream()
                .filter(item -> {
                    if (album != null && !isItemInAlbum(item, album)) return false;
                    if (tag != null && !tag.isBlank()) {
                        if (item.getTags() == null || item.getTags().stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                            return false;
                        }
                    }
                    if (mediaType != null && item.getMediaType() != mediaType) return false;
                    if (Boolean.TRUE.equals(favorite) && !item.isFavorite()) return false;
                    if (query != null && !query.isBlank()) {
                        String q = query.toLowerCase();
                        boolean matchName = item.getFilename().toLowerCase().contains(q);
                        boolean matchTag = item.getTags() != null && item.getTags().stream().anyMatch(t -> t.toLowerCase().contains(q));
                        if (!matchName && !matchTag) return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparing(PhotoItem::getDateTaken, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    public PhotoItem getPhotoById(UUID id) {
        return itemsById.get(id);
    }

    public File getPhysicalFile(UUID id) {
        PhotoItem item = itemsById.get(id);
        if (item == null) return null;
        return fileDiskStorageService.getFile(item.getPath() + item.getFilename()).toFile();
    }

    public byte[] getThumbnail(UUID id) {
        PhotoItem item = itemsById.get(id);
        if (item == null) return null;
        File f = getPhysicalFile(id);
        return thumbnailService.getOrCreateThumbnail(id, f);
    }

    public void saveThumbnail(UUID id, byte[] thumbnailBytes) {
        thumbnailService.saveThumbnail(id, thumbnailBytes);
    }

    public List<PhotoAlbum> getAlbums() {
        refreshAlbums();
        return new ArrayList<>(albumsById.values());
    }

    public PhotoAlbum getAlbumById(String id) {
        refreshAlbums();
        return albumsById.get(id);
    }

    public synchronized PhotoAlbum saveAlbum(PhotoAlbum album) {
        if (album.getId() == null || album.getId().isBlank()) {
            album.setId(UUID.randomUUID().toString());
            album.setCreatedAt(LocalDateTime.now());
        }
        albumsById.put(album.getId(), album);
        saveLibraryData();
        refreshAlbums();
        return album;
    }

    public synchronized void deleteAlbum(String id) {
        if (albumsById.remove(id) != null) {
            saveLibraryData();
        }
    }

    public Map<String, Long> getTagCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (PhotoItem item : itemsById.values()) {
            if (item.getTags() != null) {
                for (String t : item.getTags()) {
                    counts.put(t, counts.getOrDefault(t, 0L) + 1L);
                }
            }
        }
        return counts;
    }

    public synchronized PhotoItem updateTags(UUID id, Set<String> tags) {
        PhotoItem item = itemsById.get(id);
        if (item == null) return null;
        item.setTags(tags);

        PhotoCustomMetadata custom = libraryData.getItemMetadata().computeIfAbsent(id.toString(), k -> new PhotoCustomMetadata());
        custom.setTags(tags);
        saveLibraryData();
        refreshAlbums();
        return item;
    }

    public synchronized void batchUpdateTags(Set<UUID> ids, Set<String> addTags, Set<String> removeTags) {
        for (UUID id : ids) {
            PhotoItem item = itemsById.get(id);
            if (item != null) {
                Set<String> current = new HashSet<>(item.getTags() != null ? item.getTags() : Set.of());
                if (addTags != null) current.addAll(addTags);
                if (removeTags != null) current.removeAll(removeTags);
                item.setTags(current);

                PhotoCustomMetadata custom = libraryData.getItemMetadata().computeIfAbsent(id.toString(), k -> new PhotoCustomMetadata());
                custom.setTags(current);
            }
        }
        saveLibraryData();
        refreshAlbums();
    }

    public synchronized PhotoItem toggleFavorite(UUID id) {
        PhotoItem item = itemsById.get(id);
        if (item == null) return null;
        boolean next = !item.isFavorite();
        item.setFavorite(next);

        PhotoCustomMetadata custom = libraryData.getItemMetadata().computeIfAbsent(id.toString(), k -> new PhotoCustomMetadata());
        custom.setFavorite(next);
        saveLibraryData();
        return item;
    }

    public synchronized PhotoItem uploadPhoto(MultipartFile file, String subFolder, Set<String> initialTags) throws IOException {
        String targetStorage = getPhotoRelativePath();
        if (subFolder != null && !subFolder.isBlank()) {
            targetStorage = ensureStoragePathEndsWithSlash(targetStorage + "/" + subFolder.trim());
        } else {
            targetStorage = ensureStoragePathEndsWithSlash(targetStorage);
        }

        String filename = sanitizeFileName(file.getOriginalFilename());
        BervanMockMultiPartFile mockFile = new BervanMockMultiPartFile(
                filename, filename, file.getContentType(), new ByteArrayInputStream(file.getBytes())
        );

        var uploadResponse = fileServiceManager.save(mockFile, "Uploaded via Photo Library", targetStorage);
        if (uploadResponse == null || uploadResponse.getMetadata() == null || uploadResponse.getMetadata().isEmpty()) {
            throw new IOException("Failed to save file via FileServiceManager");
        }

        Metadata savedMeta = uploadResponse.getMetadata().get(0);
        Path physicalPath = fileDiskStorageService.getFile(savedMeta.getPath() + savedMeta.getFilename());

        indexFile(physicalPath, savedMeta.getPath());
        PhotoItem created = itemsById.get(savedMeta.getId());

        if (created != null && initialTags != null && !initialTags.isEmpty()) {
            created.getTags().addAll(initialTags);
            PhotoCustomMetadata custom = libraryData.getItemMetadata().computeIfAbsent(created.getId().toString(), k -> new PhotoCustomMetadata());
            custom.setTags(created.getTags());
            saveLibraryData();
        }

        refreshAlbums();
        return created;
    }

    public synchronized void deletePhoto(UUID id) {
        PhotoItem item = itemsById.remove(id);
        if (item == null) return;

        libraryData.getItemMetadata().remove(id.toString());
        for (PhotoAlbum a : albumsById.values()) {
            if (a.getPhotoIds() != null) a.getPhotoIds().remove(id);
            if (id.equals(a.getCoverPhotoId())) a.setCoverPhotoId(null);
        }

        thumbnailService.evict(id);

        try {
            List<Metadata> found = fileDBStorageService.loadByPathAndFilename(item.getPath(), item.getFilename());
            for (Metadata m : found) {
                fileServiceManager.delete(m);
            }
        } catch (Exception e) {
            log.error("Failed to delete photo metadata from DB/disk: " + id, e);
        }

        saveLibraryData();
        refreshAlbums();
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1) : "";
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "photo_" + System.currentTimeMillis() + ".jpg";
        return name.replaceAll("[^a-zA-Z0-9._\\-\\s]", "_").trim();
    }
}
