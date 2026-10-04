package org.Roclh.model;

import org.hibernate.annotations.JdbcTypeCode;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invite_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteToken {

    @Id
    @Column(name = "token", length = 64, nullable = false)
    private String token;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "created_by", nullable = false, length = 36)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "used_by", length = 36)
    private UUID usedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isUsable() {
        return usedAt == null && expiresAt.isAfter(Instant.now());
    }
}