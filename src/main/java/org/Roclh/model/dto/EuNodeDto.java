package org.Roclh.model.dto;

import org.Roclh.model.NodeStatus;

import java.time.Instant;
import java.util.UUID;

public record EuNodeDto(
        UUID id,
        String name,
        String host,
        int port,
        NodeStatus status,
        String xrayVersion,
        String agentVersion,
        String configHash,
        Instant lastHealthAt,
        String lastHealthMsg,
        Instant lastTunnelCheckAt,
        String lastTunnelIp,
        Instant enrolledAt
) {
    public static EuNodeDto from(org.Roclh.model.EuNode n) {
        return new EuNodeDto(
                n.getId(),
                n.getName(),
                n.getHost(),
                n.getPort(),
                n.getStatus(),
                n.getXrayVersion(),
                n.getAgentVersion(),
                n.getConfigHash(),
                n.getLastHealthAt(),
                n.getLastHealthMsg(),
                n.getLastTunnelCheckAt(),
                n.getLastTunnelIp(),
                n.getEnrolledAt()
        );
    }
}