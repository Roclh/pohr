--liquibase formatted sql

--changeset roclh:003-xray-configs
CREATE TABLE xray_configs
(
    id                 VARCHAR(36) PRIMARY KEY NOT NULL,
    name               VARCHAR(128)            NOT NULL UNIQUE,
    description        VARCHAR(512),
    content            TEXT                    NOT NULL,
    reality_public_key VARCHAR(64),
    active             INTEGER                 NOT NULL DEFAULT 0,
    created_at         VARCHAR(32)             NOT NULL,
    updated_at         VARCHAR(32)             NOT NULL
);

CREATE INDEX idx_xray_configs_active ON xray_configs (active);