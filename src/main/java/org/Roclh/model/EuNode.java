package org.Roclh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "eu_nodes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EuNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "reality_public_key", nullable = false, length = 64)
    private String realityPublicKey;

    @Column(name = "reality_short_id", nullable = false, length = 32)
    private String realityShortId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tunnel_uuid", nullable = false, length = 36, unique = true)
    private UUID tunnelUuid;

    @Column(name = "node_secret", nullable = false, length = 128)
    private String nodeSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeStatus status;

    @Column(name = "xray_version", length = 32)
    private String xrayVersion;

    @Column(name = "agent_version", length = 32)
    private String agentVersion;

    @Column(name = "config_hash", length = 64)
    private String configHash;

    @Column(name = "last_health_at")
    private Instant lastHealthAt;

    @Column(name = "last_health_msg", length = 512)
    private String lastHealthMsg;

    @Column(name = "last_tunnel_check_at")
    private Instant lastTunnelCheckAt;

    @Column(name = "last_tunnel_ip", length = 64)
    private String lastTunnelIp;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "enrolled_by", nullable = false, length = 36)
    private UUID enrolledBy;

    @PrePersist
    void onCreate() {
        if (enrolledAt == null) {
            enrolledAt = Instant.now();
        }
        if (status == null) {
            status = NodeStatus.PENDING;
        }
    }
}