package com.sutra.document.service.internal;

import com.sutra.api.enums.FileFormat;
import com.sutra.document.config.DocumentProperties;
import com.sutra.document.exception.InvalidQuotationFileException;
import com.sutra.document.exception.UnsupportedQuotationFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Validates an uploaded file before it is stored: name, extension, size, and that the bytes really are
 * the claimed format (magic number plus, for Office files, the expected internal structure).
 */
@Component
public class FileContentValidator {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final int MAX_ZIP_ENTRIES_SCANNED = 2_000;

    private final long maxSizeBytes;

    public FileContentValidator(DocumentProperties properties) {
        this.maxSizeBytes = properties.maxSizeBytes();
    }

    /** Returns the detected format, or throws with a message the user can act on. */
    public FileFormat validate(String originalFileName, byte[] content) {
        String fileName = sanitiseFileName(originalFileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            throw new UnsupportedQuotationFileException(
                    "Legacy Word .doc files are not supported. Save the quotation as .docx or PDF and upload again.");
        }
        FileFormat format = FileFormat.fromFileName(fileName).orElseThrow(() -> new UnsupportedQuotationFileException(
                "Unsupported file type for '" + fileName + "'. Upload a PDF, Excel (.xlsx, .xls) or Word (.docx) file."));

        if (content == null || content.length == 0) {
            throw new InvalidQuotationFileException("The file '" + fileName + "' is empty.");
        }
        if (content.length > maxSizeBytes) {
            throw new InvalidQuotationFileException("The file '" + fileName + "' is " + toMb(content.length)
                    + " MB; the maximum allowed is " + toMb(maxSizeBytes) + " MB.");
        }

        switch (format) {
            case PDF -> requireMagic(content, PDF_MAGIC, fileName, "PDF");
            case XLS -> requireMagic(content, OLE2_MAGIC, fileName, "Excel 97-2003 (.xls)");
            case XLSX -> requireOfficeZip(content, "xl/", fileName, "Excel (.xlsx)");
            case DOCX -> requireOfficeZip(content, "word/", fileName, "Word (.docx)");
        }
        return format;
    }

    /** Strips any path, control characters and quotes; keeps the name at most 255 characters. */
    public static String sanitiseFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidQuotationFileException("The uploaded file has no name.");
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new InvalidQuotationFileException("The uploaded file name is not valid.");
        }
        if (base.length() > 255) {
            int dot = base.lastIndexOf('.');
            String ext = dot > 0 ? base.substring(dot) : "";
            base = base.substring(0, 255 - ext.length()) + ext;
        }
        return base;
    }

    private static void requireMagic(byte[] content, byte[] magic, String fileName, String label) {
        if (content.length < magic.length || !Arrays.equals(Arrays.copyOf(content, magic.length), magic)) {
            throw new InvalidQuotationFileException("'" + fileName + "' is not a valid " + label
                    + " file. It may be corrupt or renamed from another format.");
        }
    }

    private static void requireOfficeZip(byte[] content, String requiredFolder, String fileName, String label) {
        requireMagic(content, ZIP_MAGIC, fileName, label);
        boolean contentTypes = false;
        boolean folder = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int scanned = 0;
            while ((entry = zip.getNextEntry()) != null && scanned++ < MAX_ZIP_ENTRIES_SCANNED) {
                String name = entry.getName();
                contentTypes |= name.equals("[Content_Types].xml");
                folder |= name.startsWith(requiredFolder);
                if (contentTypes && folder) {
                    return;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidQuotationFileException("'" + fileName + "' could not be read as " + label
                    + ". The file appears to be corrupt.");
        }
        throw new InvalidQuotationFileException("'" + fileName + "' is not a valid " + label + " document.");
    }

    private static String toMb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
