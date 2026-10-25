package com.bervan.photolibrary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoLibraryData {
    @Builder.Default
    private List<PhotoAlbum> albums = new ArrayList<>();
    @Builder.Default
    private Map<String, PhotoCustomMetadata> itemMetadata = new HashMap<>();
}
