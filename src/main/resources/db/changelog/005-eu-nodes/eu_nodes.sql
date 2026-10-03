--liquibase formatted sql

--changeset roclh:005-eu-nodes
CREATE TABLE eu_nodes (
                          id                   VARCHAR(36)  PRIMARY KEY NOT NULL,
                          name                 VARCHAR(128) NOT NULL UNIQUE,
                          host                 VARCHAR(255) NOT NULL,
                          port                 INTEGER      NOT NULL,
                          reality_public_key   VARCHAR(64)  NOT NULL,
                          reality_short_id     VARCHAR(32)  NOT NULL,
                          tunnel_uuid          VARCHAR(36)  NOT NULL,
                          node_secret          VARCHAR(128) NOT NULL,
                          status               VARCHAR(16)  NOT NULL,
                          xray_version         VARCHAR(32),
                          agent_version        VARCHAR(32),
                          config_hash          VARCHAR(64),
                          last_health_at       VARCHAR(32),
                          last_health_msg      VARCHAR(512),
                          last_tunnel_check_at VARCHAR(32),
                          last_tunnel_ip       VARCHAR(64),
                          enrolled_at          VARCHAR(32)  NOT NULL,
                          enrolled_by          VARCHAR(36)  NOT NULL
);
CREATE UNIQUE INDEX idx_eu_nodes_tunnel_uuid ON eu_nodes (tunnel_uuid);
CREATE INDEX idx_eu_nodes_status ON eu_nodes (status);

--changeset roclh:005-enrollment-tokens
CREATE TABLE enrollment_tokens (
                                   token       VARCHAR(64) PRIMARY KEY NOT NULL,
                                   node_name   VARCHAR(128) NOT NULL,
                                   created_by  VARCHAR(36)  NOT NULL,
                                   created_at  VARCHAR(32)  NOT NULL,
                                   expires_at  VARCHAR(32)  NOT NULL,
                                   used_at     VARCHAR(32)
);
CREATE INDEX idx_enrollment_tokens_expires ON enrollment_tokens (expires_at);