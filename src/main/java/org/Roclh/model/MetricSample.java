package org.Roclh.model;

import org.hibernate.annotations.JdbcTypeCode;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "metric_samples")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER", nullable = false)
    private Long id;

    @Column(name = "ts", nullable = false, length = 36)
    private String ts;

    @Column(name = "pod_cpu_pct")
    private Double podCpuPct;
    @Column(name = "pod_mem_bytes", columnDefinition = "INTEGER")
    private Long podMemBytes;
    @Column(name = "pod_mem_limit_bytes", columnDefinition = "INTEGER")
    private Long podMemLimitBytes;
    @Column(name = "jvm_heap_bytes", columnDefinition = "INTEGER")
    private Long jvmHeapBytes;
    @Column(name = "jvm_threads")
    private Integer jvmThreads;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "xray_running", nullable = false)
    private boolean xrayRunning;

    @Column(name = "xray_cpu_pct")
    private Double xrayCpuPct;
    @Column(name = "xray_mem_bytes", columnDefinition = "INTEGER")
    private Long xrayMemBytes;
    @Column(name = "xray_rx_bytes", columnDefinition = "INTEGER")
    private Long xrayRxBytes;
    @Column(name = "xray_tx_bytes", columnDefinition = "INTEGER")
    private Long xrayTxBytes;
}
