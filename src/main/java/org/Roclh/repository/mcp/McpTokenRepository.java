package org.Roclh.repository.mcp;

import org.Roclh.model.mcp.McpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpTokenRepository extends JpaRepository<McpToken, UUID> {

    Optional<McpToken> findByTokenHash(String tokenHash);

    List<McpToken> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
