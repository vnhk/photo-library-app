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
public class PhotoItem {
    private UUID id;
    private String filename;
    private String path;
    private String extension;
    private MediaType mediaType;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private LocalDateTime dateTaken;
    @Builder.Default
    private Set<String> tags = new HashSet<>();
    private boolean favorite;
    private String description;
    private Double durationSeconds;
    private String cameraModel;
}
