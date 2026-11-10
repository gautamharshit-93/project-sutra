package com.sutra.audit.repository;

import com.sutra.audit.entity.AuditChainHead;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Integer> {

    /** The chain head, locked until the current transaction ends (appends happen one batch at a time). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.id = 1")
    AuditChainHead lockHead();
}
