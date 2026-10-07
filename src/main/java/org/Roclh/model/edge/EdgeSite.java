package org.Roclh.model.edge;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "edge_sites")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeSite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "domain", nullable = false, unique = true, length = 253)
    private String domain;

    /** proxy | redirect */
    @Column(name = "site_type", nullable = false, length = 16)
    private String siteType;

    @Column(name = "upstream_host", length = 255)
    private String upstreamHost;

    @Column(name = "upstream_port")
    private Integer upstreamPort;

    /** 127.0.0.1 / 0.0.0.0 / null (= не задавать) */
    @Column(name = "bind_address", length = 64)
    private String bindAddress;

    @Column(name = "redirect_target", length = 512)
    private String redirectTarget;

    /** permanent | temporary | html */
    @Column(name = "redirect_code", length = 32)
    private String redirectCode;

    @Column(name = "tls_mode", nullable = false, length = 16)
    private String tlsMode;

    @Column(name = "acme_email", length = 255)
    private String acmeEmail;

    @Column(name = "extra_directives", columnDefinition = "TEXT")
    private String extraDirectives;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (tlsMode == null || tlsMode.isBlank()) tlsMode = "acme";
        if (siteType == null || siteType.isBlank()) siteType = "proxy";
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}