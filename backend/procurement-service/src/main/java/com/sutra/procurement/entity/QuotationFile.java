package com.sutra.procurement.entity;

import com.sutra.procurement.entity.enums.FileFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * An original uploaded quotation file, stored in PostgreSQL (BYTEA). A quotation can have several files
 * (e.g. the same offer as PDF, Word and Excel), which are extracted together.
 * Never log {@link #content}. Use {@code QuotationFileRepository.findMetadata*} when bytes are not needed.
 */
@Entity
@Table(name = "quotation_file")
@Getter
@Setter
public class QuotationFile extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", nullable = false, length = 10)
    private FileFormat fileFormat;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "uploaded_by_id", nullable = false))
    @AttributeOverride(name = "username", column = @Column(name = "uploaded_by_name", nullable = false))
    private UserRef uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Override
    public String toString() {
        return "QuotationFile[id=" + getId() + ", name=" + fileName + ", size=" + sizeBytes + "]";
    }
}
