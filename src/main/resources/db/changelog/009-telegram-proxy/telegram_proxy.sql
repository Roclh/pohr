--liquibase formatted sql

--changeset pohr:009-telegram-proxy
CREATE TABLE telegram_proxy_config
(
    name         VARCHAR(32) PRIMARY KEY,
    enabled      INTEGER     NOT NULL DEFAULT 0,
    listen_port  INTEGER     NOT NULL DEFAULT 3128,
    cover_domain VARCHAR(255),
    web_enabled  INTEGER     NOT NULL DEFAULT 0,
    updated_at   VARCHAR(36) NOT NULL
);

CREATE TABLE telegram_proxy_users
(
    user_id    VARCHAR(36) PRIMARY KEY,
    label      VARCHAR(64) NOT NULL,
    secret     VARCHAR(128),
    enabled    INTEGER     NOT NULL DEFAULT 1,
    created_at VARCHAR(36) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

INSERT INTO telegram_proxy_config (name, enabled, listen_port, web_enabled, updated_at)
VALUES ('default', 0, 3128, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

INSERT INTO telegram_proxy_users (user_id, label, secret, enabled, created_at)
SELECT id, username, NULL, 1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM users;