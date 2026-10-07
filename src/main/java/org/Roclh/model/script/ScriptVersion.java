package org.Roclh.model.script;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "script_versions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "script_id", length = 36, nullable = false)
    private UUID scriptId;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @Column(name = "version_major", nullable = false)
    private int versionMajor;

    @Column(name = "version_minor", nullable = false)
    private int versionMinor;

    @Column(name = "hash", length = 64, nullable = false)
    private String hash;

    @Column(name = "size", nullable = false)
    private int size;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 32, nullable = false)
    private ScriptVersionSource source;

    @Column(name = "snapshot_path", length = 512)
    private String snapshotPath;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "restore_of", length = 36)
    private UUID restoreOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isActive() {
        return invalidatedAt == null;
    }
}