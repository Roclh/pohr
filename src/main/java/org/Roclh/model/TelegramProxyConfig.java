package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "telegram_proxy_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramProxyConfig {

    @Id
    @Column(name = "name", length = 32, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "listen_port", nullable = false)
    private int listenPort;

    @Column(name = "cover_domain", length = 255)
    private String coverDomain;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "web_enabled", nullable = false)
    private boolean webEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
