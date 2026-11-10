package com.sutra.extraction.service.internal;

import com.sutra.extraction.config.AiProperties;
import com.sutra.extraction.exception.AiServiceException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads scanned PDFs locally and for free: Apache PDFBox renders each page to a greyscale image and the Tesseract
 * OCR engine (an installed executable, called as a separate process) turns it into text. Nothing leaves the machine.
 *
 * <p>Images are written to a private temporary folder that is deleted afterwards. File contents are never logged.</p>
 */
@Component
public class PdfOcrReader {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrReader.class);
    private static final List<String> WINDOWS_LOCATIONS = List.of(
            "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe");

    private final AiProperties.Ocr config;
    private volatile String executable;

    public PdfOcrReader(AiProperties properties) {
        this.config = properties.ai().ocr();
    }

    /** True when OCR is enabled and a Tesseract executable was found. */
    public boolean isAvailable() {
        return config.enabled() && tesseract() != null;
    }

    /** "Tesseract (eng)", for messages and the dashboard. */
    public String describe() {
        return "Tesseract OCR (" + config.languages() + ")";
    }

    /** The text of every page (up to the configured page limit), each page headed "--- page N ---". */
    public String read(String fileName, byte[] pdf) {
        String exe = tesseract();
        if (!config.enabled() || exe == null) {
            throw new AiServiceException(fileName + " looks scanned (no text layer) and OCR is not available: install "
                    + "Tesseract OCR or set TESSERACT_PATH, or enter the quotation manually.");
        }
        Path dir = null;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            dir = Files.createTempDirectory("sutra-ocr-");
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = Math.min(document.getNumberOfPages(), config.maxPages());
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, config.dpi(), ImageType.GRAY);
                Path png = dir.resolve("page-" + (i + 1) + ".png");
                ImageIO.write(image, "png", png.toFile());
                text.append("--- page ").append(i + 1).append(" ---\n").append(ocr(exe, png)).append('\n');
            }
            if (document.getNumberOfPages() > pages) {
                text.append("--- only the first ").append(pages).append(" of ").append(document.getNumberOfPages())
                        .append(" pages were read ---\n");
            }
            log.info("OCR read {} page(s) of {} ({} characters)", pages, fileName, text.length());
            return text.toString();
        } catch (IOException e) {
            throw new AiServiceException("Could not OCR " + fileName + ": " + e.getClass().getSimpleName(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Runs "tesseract page.png stdout -l eng --psm 6" (psm 6: a uniform block of text, good for tables). */
    private String ocr(String exe, Path png) throws IOException {
        Process process = new ProcessBuilder(exe, png.toString(), "stdout", "-l", config.languages(), "--psm", "6")
                .redirectErrorStream(false)
                .start();
        process.getOutputStream().close();
        byte[] out;
        try (InputStream stdout = process.getInputStream(); InputStream stderr = process.getErrorStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread drainErr = new Thread(() -> {
                try {
                    stderr.transferTo(OutputStreamSink.INSTANCE);
                } catch (IOException ignored) {
                    // diagnostics only
                }
            }, "tesseract-stderr");
            drainErr.setDaemon(true);
            drainErr.start();
            stdout.transferTo(buffer);
            out = buffer.toByteArray();
            drainErr.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AiServiceException("Interrupted while running OCR", e);
        }
        try {
            if (!process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AiServiceException("OCR took longer than " + config.timeoutSeconds() + " seconds for one page");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AiServiceException("Interrupted while running OCR", e);
        }
        if (process.exitValue() != 0) {
            throw new AiServiceException("Tesseract OCR failed (exit code " + process.exitValue() + "). Check that the "
                    + "language data for '" + config.languages() + "' is installed.");
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    /** The configured path, else the usual install folders, else "tesseract" on the PATH; null when none works. */
    String tesseract() {
        String found = executable;
        if (found != null) {
            return found;
        }
        List<String> candidates = new ArrayList<>();
        if (config.tesseractPath() != null && !config.tesseractPath().isBlank()) {
            candidates.add(config.tesseractPath().trim());
        }
        candidates.addAll(WINDOWS_LOCATIONS);
        candidates.add("/usr/bin/tesseract");
        candidates.add("/opt/homebrew/bin/tesseract");
        candidates.add("tesseract");
        for (String candidate : candidates) {
            if (!candidate.equals("tesseract") && !Files.isRegularFile(Path.of(candidate))) {
                continue;
            }
            if (runs(candidate)) {
                executable = candidate;
                log.info("Using Tesseract OCR at {}", candidate);
                return candidate;
            }
        }
        return null;
    }

    private static boolean runs(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStreamSink.INSTANCE);
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp folder; the OS cleans it up eventually
        }
    }

    /** Discards a process stream. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
