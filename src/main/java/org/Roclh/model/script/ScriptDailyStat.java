package org.Roclh.model.script;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "script_daily_stats")
@IdClass(ScriptDailyStatId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptDailyStat {

    /** YYYY-MM-DD */
    @Id
    @Column(name = "day", length = 10, nullable = false)
    private String day;

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "script_id", length = 36, nullable = false)
    private UUID scriptId;

    @Id
    @Column(name = "script_version", length = 32, nullable = false)
    private String scriptVersion;

    @Column(name = "errors_count", nullable = false)
    private int errorsCount;

    @Column(name = "syncs_count", nullable = false)
    private int syncsCount;

    @Column(name = "downloads_count", nullable = false)
    private int downloadsCount;
}