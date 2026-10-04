package org.Roclh.repository;

import org.Roclh.model.MetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {

    List<MetricSample> findByTsGreaterThanEqualOrderByTsAsc(String fromTs);

    @Modifying
    @Transactional
    @Query("delete from MetricSample m where m.ts < :before")
    int deleteOlderThan(@Param("before") String before);
}