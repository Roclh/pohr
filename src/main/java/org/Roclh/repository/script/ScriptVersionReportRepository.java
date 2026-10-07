package org.Roclh.repository.script;

import org.Roclh.model.script.ScriptVersionReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScriptVersionReportRepository extends JpaRepository<ScriptVersionReport, UUID> {

    List<ScriptVersionReport> findTop100ByUserIdOrderByReportedAtDesc(UUID userId);

    List<ScriptVersionReport> findTop100ByScriptIdOrderByReportedAtDesc(UUID scriptId);

    @Modifying
    @Transactional
    @Query("delete from ScriptVersionReport r where r.reportedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}