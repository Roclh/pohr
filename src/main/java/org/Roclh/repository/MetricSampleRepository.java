package org.Roclh.repository;

import org.Roclh.model.MetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {

    List<MetricSample> findByTsGreaterThanEqualOrderByTsAsc(String fromTs);

    /** Последняя точка без выгрузки всей таблицы (раньше здесь был findAll). */
    Optional<MetricSample> findTopByOrderByIdDesc();

    /**
     * Агрегаты за окно — считает SQLite, в heap попадает одна строка.
     * Порядок колонок: см. {@code MetricsService#stats}.
     */
    @Query(value = """
            select count(*),
                   coalesce(min(pod_mem_bytes), 0), coalesce(avg(pod_mem_bytes), 0), coalesce(max(pod_mem_bytes), 0),
                   coalesce(min(pod_anon_bytes), 0), coalesce(avg(pod_anon_bytes), 0), coalesce(max(pod_anon_bytes), 0),
                   coalesce(min(xray_mem_bytes), 0), coalesce(avg(xray_mem_bytes), 0), coalesce(max(xray_mem_bytes), 0),
                   coalesce(min(telemt_mem_bytes), 0), coalesce(avg(telemt_mem_bytes), 0), coalesce(max(telemt_mem_bytes), 0),
                   coalesce(min(jvm_heap_bytes), 0), coalesce(avg(jvm_heap_bytes), 0), coalesce(max(jvm_heap_bytes), 0),
                   coalesce(min(pod_cpu_pct), 0), coalesce(avg(pod_cpu_pct), 0), coalesce(max(pod_cpu_pct), 0),
                   coalesce(max(pod_mem_limit_bytes), 0),
                   coalesce(sum(case when pod_mem_limit_bytes > 0
                                      and pod_mem_bytes * 100 >= pod_mem_limit_bytes * 95 then 1 else 0 end), 0),
                   coalesce(sum(case when pod_mem_limit_bytes > 0
                                      and pod_anon_bytes * 100 >= pod_mem_limit_bytes * 95 then 1 else 0 end), 0)
            from metric_samples
            where ts >= :from
            """, nativeQuery = true)
    List<Object[]> aggregateSince(@Param("from") String from);

    /**
     * Ряды, свёрнутые по часу ({@code len=13}) или по суткам ({@code len=10}).
     * Ограничение по числу строк задаёт вызывающая сторона — она же решает,
     * нужны ли графику минуты или часов хватает.
     */
    @Query(value = """
            select substr(ts, 1, :len) as bucket,
                   count(*),
                   coalesce(avg(pod_anon_bytes), 0), coalesce(max(pod_anon_bytes), 0),
                   coalesce(avg(pod_mem_bytes), 0), coalesce(max(pod_mem_bytes), 0),
                   coalesce(avg(xray_mem_bytes), 0), coalesce(max(xray_mem_bytes), 0),
                   coalesce(avg(jvm_heap_bytes), 0)
            from metric_samples
            where ts >= :from
            group by bucket
            order by bucket
            """, nativeQuery = true)
    List<Object[]> bucketsSince(@Param("from") String from, @Param("len") int len);

    @Modifying
    @Transactional
    @Query("delete from MetricSample m where m.ts < :before")
    int deleteOlderThan(@Param("before") String before);
}
