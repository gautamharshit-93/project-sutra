package com.sutra.extraction.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sutra.extraction.config.AiProperties;
import com.sutra.extraction.exception.AiServiceException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

/** OCR of a scanned (image-only) PDF with PDFBox + Tesseract. Skipped where Tesseract is not installed. */
class PdfOcrReaderTest {

    private static AiProperties props(boolean enabled) {
        return new AiProperties(new AiProperties.Files(1024),
                new AiProperties.Ai("http://localhost:8000", 5, null,
                        new AiProperties.Ocr(enabled, null, "eng", 300, 5, 60),
                        new BigDecimal("0.75"), new BigDecimal("0.6")));
    }

    /** A one-page PDF that contains only a picture of some text, like a scan. */
    private static byte[] scannedPdf(String... lines) throws Exception {
        BufferedImage image = new BufferedImage(1700, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 54));
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 60, 130 + i * 110);
        }
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(612, 216));
            doc.addPage(page);
            PDImageXObject picture = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(picture, 0, 0, 612, 216);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsTheTextOfAScannedPage() throws Exception {
        PdfOcrReader reader = new PdfOcrReader(props(true));
        assumeTrue(reader.isAvailable(), "Tesseract OCR is not installed");

        String text = reader.read("scan.pdf", scannedPdf("QUOTATION NO 118", "Business Laptop 62500"));

        assertThat(text).startsWith("--- page 1 ---").contains("QUOTATION").contains("Laptop").contains("62500");
    }

    @Test
    void explainsWhatToDoWhenOcrIsOff() {
        PdfOcrReader reader = new PdfOcrReader(props(false));

        assertThat(reader.isAvailable()).isFalse();
        assertThatThrownBy(() -> reader.read("scan.pdf", new byte[] {1}))
                .isInstanceOf(AiServiceException.class).hasMessageContaining("install Tesseract OCR");
    }
}
