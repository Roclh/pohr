package org.Roclh.repository.traffic;

import org.Roclh.model.ClientTrafficSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ClientTrafficSampleRepository extends JpaRepository<ClientTrafficSample, Long> {

    @Modifying
    @Transactional
    @Query("delete from ClientTrafficSample s where s.ts < :before")
    int deleteOlderThan(@Param("before") String before);
}
