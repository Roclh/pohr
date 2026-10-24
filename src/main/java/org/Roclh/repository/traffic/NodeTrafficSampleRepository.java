package org.Roclh.repository.traffic;

import org.Roclh.model.NodeTrafficSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface NodeTrafficSampleRepository extends JpaRepository<NodeTrafficSample, Long> {

    Optional<NodeTrafficSample> findTopByOrderByTsDesc();

    List<NodeTrafficSample> findByTsGreaterThanEqualOrderByTsAsc(String ts);

    @Modifying
    @Transactional
    @Query("delete from NodeTrafficSample s where s.ts < :before")
    int deleteOlderThan(@Param("before") String before);
}
