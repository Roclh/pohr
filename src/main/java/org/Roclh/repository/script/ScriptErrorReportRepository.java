package org.Roclh.repository.script;

import org.Roclh.model.script.ScriptErrorReport;
import org.Roclh.model.script.ScriptErrorStatus;
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
public interface ScriptErrorReportRepository extends JpaRepository<ScriptErrorReport, UUID> {

    List<ScriptErrorReport> findAllByOrderByReceivedAtDesc();

    List<ScriptErrorReport> findByStatusOrderByReceivedAtDesc(ScriptErrorStatus status);

    List<ScriptErrorReport> findByUserIdOrderByReceivedAtDesc(UUID userId);

    long countByStatus(ScriptErrorStatus status);

    long countByReceivedAtAfter(Instant since);

    @Modifying
    @Transactional
    @Query("delete from ScriptErrorReport r where r.status <> 'RESOLVED' and r.receivedAt < :before")
    int deleteStaleUnresolved(@Param("before") Instant before);
}