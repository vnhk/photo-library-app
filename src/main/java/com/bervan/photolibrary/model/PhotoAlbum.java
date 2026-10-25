package com.bervan.photolibrary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoAlbum {
    private String id;
    private String name;
    private String description;
    private UUID coverPhotoId;
    @Builder.Default
    private Set<String> tags = new HashSet<>();
    @Builder.Default
    private Set<UUID> photoIds = new HashSet<>();
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    private Integer photoCount;
}
