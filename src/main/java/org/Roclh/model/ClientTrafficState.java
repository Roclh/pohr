package org.Roclh.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Последние сырые значения счётчиков Xray по клиенту.
 * Нужны, чтобы считать дельты между опросами и переживать рестарт приложения:
 * сами счётчики Xray живут до его перезапуска, а дельты — в этой таблице.
 */
@Entity
@Table(name = "client_traffic_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_traffic_state",
                columnNames = {"node_id", "email"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientTrafficState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER", nullable = false)
    private Long id;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(nullable = false, length = 64)
    private String email;

    @Column(name = "raw_uplink", nullable = false, columnDefinition = "INTEGER")
    private long rawUplink;

    @Column(name = "raw_downlink", nullable = false, columnDefinition = "INTEGER")
    private long rawDownlink;

    @Column(name = "updated_at", nullable = false, length = 36)
    private String updatedAt;
}
