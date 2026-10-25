package com.bervan.photolibrary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoCustomMetadata {
    @Builder.Default
    private Set<String> tags = new HashSet<>();
    private Boolean favorite;
    private String description;
    private LocalDateTime customDateTaken;
    private Integer width;
    private Integer height;
    private String cameraModel;
    private Double durationSeconds;
}
