--liquibase formatted sql

--changeset pohr:006-invite-tokens
CREATE TABLE invite_tokens (
                               token      VARCHAR(64)  NOT NULL PRIMARY KEY,
                               role       VARCHAR(16)  NOT NULL,
                               created_by VARCHAR(36)  NOT NULL,
                               created_at VARCHAR(36)  NOT NULL,
                               expires_at VARCHAR(36)  NOT NULL,
                               used_at    VARCHAR(36),
                               used_by    VARCHAR(36)
);

CREATE INDEX idx_invite_tokens_expires ON invite_tokens (expires_at);
--rollback DROP TABLE invite_tokens;