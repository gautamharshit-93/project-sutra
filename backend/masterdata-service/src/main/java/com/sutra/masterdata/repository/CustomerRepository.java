package com.sutra.masterdata.repository;

import com.sutra.masterdata.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    long countByEntityId(Long entityId);

    @Query("""
            select c from Customer c
            where :q is null
               or lower(c.name) like lower(concat('%', cast(:q as string), '%'))
               or lower(c.code) like lower(concat('%', cast(:q as string), '%'))
            """)
    Page<Customer> search(@Param("q") String q, Pageable pageable);
}
