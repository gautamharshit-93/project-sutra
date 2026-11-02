package com.sutra.masterdata.repository;

import com.sutra.masterdata.entity.BusinessEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessEntityRepository extends JpaRepository<BusinessEntity, Long> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
            select e from BusinessEntity e
            where :q is null
               or lower(e.name) like lower(concat('%', cast(:q as string), '%'))
               or lower(e.code) like lower(concat('%', cast(:q as string), '%'))
            """)
    Page<BusinessEntity> search(@Param("q") String q, Pageable pageable);
}
