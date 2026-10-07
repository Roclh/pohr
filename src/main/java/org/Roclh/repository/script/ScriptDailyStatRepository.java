package org.Roclh.repository.script;

import org.Roclh.model.script.ScriptDailyStat;
import org.Roclh.model.script.ScriptDailyStatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScriptDailyStatRepository extends JpaRepository<ScriptDailyStat, ScriptDailyStatId> {

    Optional<ScriptDailyStat> findByDayAndScriptIdAndScriptVersion(String day, UUID scriptId, String scriptVersion);

    List<ScriptDailyStat> findByScriptIdAndDayGreaterThanEqualOrderByDayDesc(UUID scriptId, String sinceDay);

    @Modifying
    @Transactional
    @Query("delete from ScriptDailyStat s where s.day < :before")
    int deleteOlderThan(@Param("before") String beforeDay);
}