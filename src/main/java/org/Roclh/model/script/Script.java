package org.Roclh.model.script;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scripts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", length = 128, nullable = false, unique = true)
    private String name;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    /** windows | linux | macos | any */
    @Column(name = "platform", length = 32, nullable = false)
    private String platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", length = 16, nullable = false)
    private ScriptAccessLevel accessLevel;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "is_entrypoint", nullable = false)
    private boolean entrypoint;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "is_seeded", nullable = false)
    private boolean seeded;

    @Column(name = "seed_version", length = 32)
    private String seedVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (platform == null || platform.isBlank()) platform = "any";
        if (accessLevel == null) accessLevel = ScriptAccessLevel.ADMIN;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}