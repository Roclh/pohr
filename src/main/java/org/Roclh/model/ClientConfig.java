package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;


@Entity
@Table(name = "client_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientConfig {

    @Id
    @Column(name = "client_name", length = 32, nullable = false)
    private String clientName;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "user_agent_pattern", nullable = false, length = 128)
    private String userAgentPattern;

    @Column(name = "headers", nullable = false, columnDefinition = "TEXT")
    private String headers;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}