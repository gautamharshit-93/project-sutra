package com.sutra.masterdata.repository;

import com.sutra.masterdata.entity.Vendor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorRepository extends JpaRepository<Vendor, Long> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
            select v from Vendor v
            where :q is null
               or lower(v.name) like lower(concat('%', cast(:q as string), '%'))
               or lower(v.code) like lower(concat('%', cast(:q as string), '%'))
            """)
    Page<Vendor> search(@Param("q") String q, Pageable pageable);
}
