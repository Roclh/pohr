package org.Roclh.repository;

import org.Roclh.model.XrayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface XrayConfigRepository extends JpaRepository<XrayConfig, UUID> {
    Optional<XrayConfig> findByActiveTrue();
    Optional<XrayConfig> findByName(String name);
    boolean existsByName(String name);
    List<XrayConfig> findAllByOrderByCreatedAtDesc();
}
