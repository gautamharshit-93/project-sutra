package com.sutra.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code sutra.files.*}: the upload limit and how long an unattached upload is kept. */
@ConfigurationProperties(prefix = "sutra.files")
public record DocumentProperties(long maxSizeBytes, int orphanAfterMinutes) {

    public DocumentProperties {
        maxSizeBytes = maxSizeBytes <= 0 ? 20L * 1024 * 1024 : maxSizeBytes;
        orphanAfterMinutes = orphanAfterMinutes <= 0 ? 60 : orphanAfterMinutes;
    }
}
