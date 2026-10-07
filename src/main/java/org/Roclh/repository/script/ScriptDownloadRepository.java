package org.Roclh.repository.script;

import org.Roclh.model.script.ScriptDownload;
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
public interface ScriptDownloadRepository extends JpaRepository<ScriptDownload, UUID> {

    Optional<ScriptDownload> findFirstByUserIdAndScriptIdOrderByDownloadedAtDesc(UUID userId, UUID scriptId);

    List<ScriptDownload> findTop50ByScriptIdOrderByDownloadedAtDesc(UUID scriptId);

    @Modifying
    @Transactional
    @Query("delete from ScriptDownload d where d.downloadedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);

    @Modifying
    @Transactional
    @Query(value = """
            delete from script_downloads
            where id in (
                select id from script_downloads
                where user_id = :userId
                order by downloaded_at desc
                limit -1 offset :keep
            )
            """, nativeQuery = true)
    int deleteOldForUser(@Param("userId") UUID userId, @Param("keep") int keep);
}