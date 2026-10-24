package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Агрегат по ноде за интервал: сколько TCP-соединений к Xray было активно,
 * сколько клиентов реально передавали трафик и сколько байт прошло всего.
 * Используется для трезвой оценки загрузки ноды, без хранения per-flow строк.
 */
@Entity
@Table(name = "node_traffic_samples")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeTrafficSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER", nullable = false)
    private Long id;

    @Column(name = "ts", nullable = false, length = 36)
    private String ts;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "active_connections")
    private Integer activeConnections;

    @Column(name = "active_users")
    private Integer activeUsers;

    @Column(name = "uplink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long uplinkBytes;

    @Column(name = "downlink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long downlinkBytes;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "xray_available", nullable = false)
    private boolean xrayAvailable;
}
