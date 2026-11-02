package com.sutra.masterdata.repository;

import com.sutra.masterdata.entity.SubEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubEntityRepository extends JpaRepository<SubEntity, Long> {
    List<SubEntity> findByEntityIdOrderByCode(Long entityId);

    boolean existsByEntityIdAndCodeIgnoreCase(Long entityId, String code);

    boolean existsByEntityIdAndCodeIgnoreCaseAndIdNot(Long entityId, String code, Long id);

    long countByEntityId(Long entityId);
}
