package com.sutra.reporting.dto.response;

/** The award summary PDF and the file name to download it as. */
public record AwardSummary(String fileName, byte[] pdf) {

    @Override
    public String toString() {
        return "AwardSummary[" + fileName + ", " + pdf.length + " bytes]";
    }
}
