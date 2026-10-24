package org.Roclh.repository.traffic;

import org.Roclh.model.ClientTrafficDaily;
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
public interface ClientTrafficDailyRepository extends JpaRepository<ClientTrafficDaily, Long> {

    List<ClientTrafficDaily> findByDayGreaterThanEqual(String day);

    Optional<ClientTrafficDaily> findByDayAndNodeIdAndUserId(String day, String nodeId, UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientTrafficDaily d where d.day < :before")
    int deleteBefore(@Param("before") String before);
}
