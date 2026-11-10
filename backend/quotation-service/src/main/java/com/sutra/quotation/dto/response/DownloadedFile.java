package com.sutra.quotation.dto.response;

/** An original file as downloaded: name, type and bytes (never logged). */
public record DownloadedFile(String fileName, String contentType, long sizeBytes, byte[] content) {

    @Override
    public String toString() {
        return "DownloadedFile[" + fileName + ", " + sizeBytes + " bytes]";
    }
}
