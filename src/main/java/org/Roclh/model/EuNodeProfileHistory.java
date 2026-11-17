package org.Roclh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Последние профили REALITY узла (Т03). Хранит предыдущие значения, чтобы
 * кнопка «Откатить» возвращала рабочую пару sni/target/fingerprint.
 */
@Entity
@Table(name = "eu_node_profile_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EuNodeProfileHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "node_id", nullable = false, length = 36)
    private UUID nodeId;

    @Column(length = 253)
    private String sni;

    @Column(length = 255)
    private String target;

    @Column(length = 16)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;
}
