package org.Roclh.repository.script;

import org.Roclh.model.script.ScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, UUID> {

    Optional<ScriptVersion> findByScriptIdAndInvalidatedAtIsNull(UUID scriptId);

    List<ScriptVersion> findByScriptIdOrderByVersionMajorDescVersionMinorDesc(UUID scriptId);

    boolean existsByScriptIdAndHashAndInvalidatedAtIsNull(UUID scriptId, String hash);

    @Modifying
    @Transactional
    @Query("update ScriptVersion v set v.invalidatedAt = :ts where v.scriptId = :scriptId and v.invalidatedAt is null")
    int invalidateActive(@Param("scriptId") UUID scriptId, @Param("ts") Instant ts);
}