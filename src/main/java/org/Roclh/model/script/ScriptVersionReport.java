package org.Roclh.model.script;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "script_version_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptVersionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "script_id", length = 36, nullable = false)
    private UUID scriptId;

    @Column(name = "version", length = 32, nullable = false)
    private String version;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "root_script_id", length = 36)
    private UUID rootScriptId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @PrePersist
    void onCreate() {
        if (reportedAt == null) reportedAt = Instant.now();
    }
}