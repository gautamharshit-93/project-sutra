package com.sutra.document.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sutra.api.enums.FileFormat;
import com.sutra.document.exception.InvalidQuotationFileException;
import com.sutra.document.exception.UnsupportedQuotationFileException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileContentValidatorTest {

    private final FileContentValidator validator = new FileContentValidator(new com.sutra.document.config.DocumentProperties(1024 * 1024, 60)); // 1 MB limit

    private static byte[] officeZip(String folder) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(folder + "document.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void acceptsRealPdf() {
        assertThat(validator.validate("quote.PDF", "%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(FileFormat.PDF);
    }

    @Test
    void acceptsRealXlsxAndDocx() throws IOException {
        assertThat(validator.validate("q.xlsx", officeZip("xl/"))).isEqualTo(FileFormat.XLSX);
        assertThat(validator.validate("q.docx", officeZip("word/"))).isEqualTo(FileFormat.DOCX);
    }

    @Test
    void rejectsDocxRenamedAsXlsx() throws IOException {
        byte[] docx = officeZip("word/");
        assertThatThrownBy(() -> validator.validate("q.xlsx", docx))
                .isInstanceOf(InvalidQuotationFileException.class)
                .hasMessageContaining("not a valid Excel");
    }

    @Test
    void rejectsExecutableRenamedAsPdf() {
        assertThatThrownBy(() -> validator.validate("quote.pdf", new byte[]{'M', 'Z', 0, 0, 1, 2}))
                .isInstanceOf(InvalidQuotationFileException.class)
                .hasMessageContaining("corrupt or renamed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"quote.txt", "quote.csv", "quote.exe", "quote"})
    void rejectsUnsupportedTypes(String name) {
        assertThatThrownBy(() -> validator.validate(name, new byte[]{1}))
                .isInstanceOf(UnsupportedQuotationFileException.class)
                .hasMessageContaining("PDF, Excel");
    }

    @Test
    void legacyDocGetsAnActionableMessage() {
        assertThatThrownBy(() -> validator.validate("quote.doc", new byte[]{1}))
                .isInstanceOf(UnsupportedQuotationFileException.class)
                .hasMessageContaining(".docx");
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> validator.validate("q.pdf", new byte[0]))
                .isInstanceOf(InvalidQuotationFileException.class).hasMessageContaining("empty");
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 5);
        assertThatThrownBy(() -> validator.validate("q.pdf", big))
                .isInstanceOf(InvalidQuotationFileException.class).hasMessageContaining("maximum");
    }

    @Test
    void sanitisesPathsAndControlCharacters() {
        assertThat(FileContentValidator.sanitiseFileName("C:\\fakepath\\..\\vendor\"quote\u0001.pdf"))
                .isEqualTo("vendor_quote_.pdf");
        assertThat(FileContentValidator.sanitiseFileName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThatThrownBy(() -> FileContentValidator.sanitiseFileName(".."))
                .isInstanceOf(InvalidQuotationFileException.class);
    }
}
