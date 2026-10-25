package com.bervan.photolibrary.service;

import com.bervan.logging.JsonLogger;
import com.bervan.photolibrary.model.MediaType;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Service
public class PhotoMetadataExtractorService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "photo-library");

    public record ExtractedMetadata(
            LocalDateTime dateTaken,
            Integer width,
            Integer height,
            String cameraModel,
            Double durationSeconds
    ) {}

    public ExtractedMetadata extract(File file, MediaType mediaType) {
        LocalDateTime dateTaken = null;
        Integer width = null;
        Integer height = null;
        String cameraModel = null;
        Double duration = null;

        if (file == null || !file.exists()) {
            return new ExtractedMetadata(null, null, null, null, null);
        }

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);

            // EXIF Date taken
            ExifSubIFDDirectory subDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subDir != null) {
                Date d = subDir.getDateOriginal();
                if (d == null) {
                    d = subDir.getDateDigitized();
                }
                if (d != null) {
                    dateTaken = LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
                }
            }

            // Fallback date from IFD0
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                if (dateTaken == null) {
                    Date d = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
                    if (d != null) {
                        dateTaken = LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
                    }
                }
                String make = ifd0.getString(ExifIFD0Directory.TAG_MAKE);
                String model = ifd0.getString(ExifIFD0Directory.TAG_MODEL);
                if (make != null || model != null) {
                    cameraModel = ((make != null ? make.trim() : "") + " " + (model != null ? model.trim() : "")).trim();
                }
            }

            // Dimensions for photos
            if (mediaType == MediaType.PHOTO) {
                JpegDirectory jpegDir = metadata.getFirstDirectoryOfType(JpegDirectory.class);
                if (jpegDir != null) {
                    try {
                        width = jpegDir.getImageWidth();
                        height = jpegDir.getImageHeight();
                    } catch (Exception ignored) {}
                }
            }

            // Video metadata (MP4 / QuickTime)
            if (mediaType == MediaType.VIDEO) {
                Mp4VideoDirectory mp4Dir = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
                if (mp4Dir != null) {
                    try {
                        width = mp4Dir.getInt(Mp4VideoDirectory.TAG_WIDTH);
                        height = mp4Dir.getInt(Mp4VideoDirectory.TAG_HEIGHT);
                    } catch (Exception ignored) {}
                }
                QuickTimeVideoDirectory qtDir = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
                if (qtDir != null) {
                    try {
                        if (width == null) width = qtDir.getInt(QuickTimeVideoDirectory.TAG_WIDTH);
                        if (height == null) height = qtDir.getInt(QuickTimeVideoDirectory.TAG_HEIGHT);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("Metadata extractor skipped for {}: {}", file.getName(), e.getMessage());
        }

        // Fallback for photo width/height via ImageIO
        if (mediaType == MediaType.PHOTO && (width == null || height == null)) {
            try {
                BufferedImage bimg = ImageIO.read(file);
                if (bimg != null) {
                    width = bimg.getWidth();
                    height = bimg.getHeight();
                }
            } catch (Exception ignored) {}
        }

        return new ExtractedMetadata(dateTaken, width, height, cameraModel, duration);
    }
}
