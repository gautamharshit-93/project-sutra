package com.sutra.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * The last link of the audit hash chain (a single row, id = 1). It is locked while entries are appended, so
 * concurrent batches are chained one after the other and never fork.
 */
@Entity
@Table(name = "audit_chain_head")
@Getter
public class AuditChainHead {

    @Id
    private Integer id;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    protected AuditChainHead() {
    }

    public AuditChainHead(long lastSequence, String lastHash) {
        this.id = 1;
        this.lastSequence = lastSequence;
        this.lastHash = lastHash;
    }

    public void advance(long sequence, String hash) {
        this.lastSequence = sequence;
        this.lastHash = hash;
    }
}
