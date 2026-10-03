package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "xray_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XrayConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private String content;

    /** Публичный ключ Reality (соответствует privateKey внутри content). Нужен для генерации vless://-ссылок. */
    @Column(name = "reality_public_key", length = 64)
    private String realityPublicKey;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}