package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Суточный роллап трафика клиента. Дневная таблица переживает чистку
 * минутных сэмплов и используется для отчётов «сегодня / 7 дней».
 */
@Entity
@Table(name = "client_traffic_daily",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_traffic_daily",
                columnNames = {"day", "node_id", "user_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientTrafficDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER", nullable = false)
    private Long id;

    @Column(name = "day", nullable = false, length = 10)
    private String day;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "uplink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long uplinkBytes;

    @Column(name = "downlink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long downlinkBytes;

    @Column(name = "last_seen_at", nullable = false, length = 36)
    private String lastSeenAt;
}
