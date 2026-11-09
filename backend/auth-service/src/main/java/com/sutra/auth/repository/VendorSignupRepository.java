package com.sutra.auth.repository;

import com.sutra.auth.entity.VendorSignup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorSignupRepository extends JpaRepository<VendorSignup, Long> {

    Optional<VendorSignup> findByUserId(Long userId);

    @EntityGraph(attributePaths = "user")
    List<VendorSignup> findAllByOrderByCreatedAtAsc();
}
