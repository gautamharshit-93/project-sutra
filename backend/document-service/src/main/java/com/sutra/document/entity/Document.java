package com.sutra.document.entity;

import com.sutra.api.enums.FileFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One original file, byte for byte as uploaded. It is PENDING until the owning service confirms the record it
 * belongs to (ATTACHED); a PENDING upload older than {@code sutra.files.orphan-after-minutes} is removed.
 * The bytes are loaded only when the file is downloaded or read (see {@code DocumentRepository}).
 */
@Entity
@Table(name = "document")
@Getter
@Setter
public class Document {

    public enum Status { PENDING, ATTACHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_type", length = 30)
    private String ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "file_name", nullable = false, length = 255)
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

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "uploaded_by_id", nullable = false)
    private Long uploadedById;

    @Column(name = "uploaded_by_name", nullable = false, length = 60)
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Override
    public String toString() {
        return "Document[" + id + ", " + fileName + ", " + sizeBytes + " bytes]"; // never the content
    }
}
