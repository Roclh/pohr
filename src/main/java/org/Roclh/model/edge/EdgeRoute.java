package org.Roclh.model.edge;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "edge_routes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sni", nullable = false, unique = true, length = 253)
    private String sni;

    @Column(name = "target_host", nullable = false, length = 255)
    private String targetHost;

    @Column(name = "target_port", nullable = false)
    private int targetPort;

    @Column(name = "protocol", nullable = false, length = 16)
    private String protocol;

    @Column(name = "description", length = 255)
    private String description;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (protocol == null || protocol.isBlank()) protocol = "tcp";
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}