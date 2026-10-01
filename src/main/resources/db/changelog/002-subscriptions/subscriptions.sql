--liquibase formatted sql

--changeset Roclh:003-create-subscriptions
CREATE TABLE subscriptions
(
    id         VARCHAR(36) PRIMARY KEY,
    user_id    VARCHAR(36) NOT NULL,
    token      VARCHAR(64) NOT NULL UNIQUE,
    enabled    INTEGER     NOT NULL DEFAULT 1,
    created_at TEXT        NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_subscriptions_token ON subscriptions (token);
--rollback DROP TABLE subscriptions;