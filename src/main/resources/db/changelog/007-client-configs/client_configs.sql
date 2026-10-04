--liquibase formatted sql

--changeset pohr:007-client-configs
CREATE TABLE client_configs
(
    client_name        VARCHAR(32)  NOT NULL PRIMARY KEY,
    enabled            INTEGER      NOT NULL DEFAULT 1,
    user_agent_pattern VARCHAR(128) NOT NULL,
    headers            TEXT         NOT NULL DEFAULT '',
    updated_at         VARCHAR(36)  NOT NULL
);
--rollback DROP TABLE client_configs;