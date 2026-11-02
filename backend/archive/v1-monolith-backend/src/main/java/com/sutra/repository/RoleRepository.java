package com.sutra.repository;

import com.sutra.entity.Role;
import com.sutra.entity.enums.RoleName;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(RoleName name);

    List<Role> findByNameIn(Collection<RoleName> names);
}
