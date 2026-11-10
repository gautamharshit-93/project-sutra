package com.sutra.extraction.entity;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.enums.ExtractionSource;
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
 * One reading of one quotation: QUEUED when asked for, RUNNING while the engines read it, then SUCCEEDED (the
 * result was handed to the quotation service), FAILED (with the reason) or SKIPPED (the quotation was already
 * being read). Kept as a history of every reading: which engine, model, how long, and what went wrong.
 */
@Entity
@Table(name = "extraction_job")
@Getter
@Setter
public class ExtractionJob {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quotation_id", nullable = false)
    private Long quotationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExtractionEngine engine = ExtractionEngine.AUTO;

    /** The Groq model asked for first, or null for the configured order. */
    @Column(name = "preferred_model", length = 100)
    private String preferredModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.QUEUED;

    /** The engine that produced the result. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExtractionSource source;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "items_read")
    private Integer itemsRead;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
