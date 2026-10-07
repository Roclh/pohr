package org.Roclh.model.edge;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "edge_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeConfig {

    @Id
    @Column(name = "name", length = 32, nullable = false)
    private String name;

    @Column(name = "stream_port", nullable = false)
    private int streamPort;

    @Column(name = "http_port", nullable = false)
    private int httpPort;

    @Column(name = "https_port", nullable = false)
    private int httpsPort;

    @Column(name = "fallback_target", nullable = false, length = 255)
    private String fallbackTarget;

    @Column(name = "worker_processes", nullable = false, length = 16)
    private String workerProcesses;

    @Column(name = "worker_connections", nullable = false)
    private int workerConnections;

    @Column(name = "stream_proxy_timeout", nullable = false, length = 16)
    private String streamProxyTimeout;

    @Column(name = "stream_connect_timeout", nullable = false, length = 16)
    private String streamConnectTimeout;

    /** on | disable_redirects | off | disable_certs */
    @Column(name = "auto_https", nullable = false, length = 32)
    private String autoHttps;

    @Column(name = "extra_stream_directives", columnDefinition = "TEXT")
    private String extraStreamDirectives;

    @Column(name = "extra_http_directives", columnDefinition = "TEXT")
    private String extraHttpDirectives;

    @Column(name = "extra_global_directives", columnDefinition = "TEXT")
    private String extraGlobalDirectives;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}