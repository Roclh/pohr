package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Дельта трафика одного клиента за интервал опроса.
 * node_id — задел на мультисерверный мост: каждая RU-нода пишет под своим id.
 */
@Entity
@Table(name = "client_traffic_samples")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientTrafficSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER", nullable = false)
    private Long id;

    @Column(name = "ts", nullable = false, length = 36)
    private String ts;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subscription_id", length = 36)
    private UUID subscriptionId;

    @Column(nullable = false, length = 64)
    private String email;

    @Column(name = "uplink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long uplinkBytes;

    @Column(name = "downlink_bytes", nullable = false, columnDefinition = "INTEGER")
    private long downlinkBytes;
}
