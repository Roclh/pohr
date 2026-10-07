package org.Roclh.model.script;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "script_error_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptErrorReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "root_script_id", length = 36)
    private UUID rootScriptId;

    @Column(name = "root_script_version", length = 32)
    private String rootScriptVersion;

    @Column(name = "stage", length = 64)
    private String stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ScriptErrorStatus status;

    @Column(name = "report_text", columnDefinition = "TEXT", nullable = false)
    private String reportText;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "resolved_by", length = 36)
    private UUID resolvedBy;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = Instant.now();
        if (status == null) status = ScriptErrorStatus.NEW;
    }
}