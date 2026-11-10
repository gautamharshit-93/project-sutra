package com.sutra.api.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Quotation file formats the system accepts, with their canonical MIME type. */
public enum FileFormat {
    PDF("pdf", "application/pdf"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    XLS("xls", "application/vnd.ms-excel"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String extension;
    private final String mimeType;

    FileFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String mimeType() {
        return mimeType;
    }

    public static Optional<FileFormat> fromFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return Optional.empty();
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(ext)).findFirst();
    }
}
