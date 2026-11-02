package com.sutra.auth.repository;

import com.sutra.auth.entity.AppUser;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    long countByVendorId(Long vendorId);

    @Query("""
            select u from AppUser u
            where :q is null
               or lower(u.username) like lower(concat('%', cast(:q as string), '%'))
               or lower(u.fullName) like lower(concat('%', cast(:q as string), '%'))
               or lower(u.email) like lower(concat('%', cast(:q as string), '%'))
            """)
    Page<AppUser> search(@Param("q") String q, Pageable pageable);
}
