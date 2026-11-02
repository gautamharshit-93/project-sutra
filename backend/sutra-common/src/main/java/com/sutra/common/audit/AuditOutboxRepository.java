package com.sutra.common.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, Long> {

    List<AuditOutboxEvent> findTop100ByOrderByIdAsc();
}
