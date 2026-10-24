--liquibase formatted sql

-- ============================================================
-- Авторство изменений скриптов.
--
-- changed_by / change_note нужны, чтобы UI и MCP могли ответить
-- на вопрос «кто и зачем последним менял скрипт». Старые строки
-- остаются с NULL — для них показываем "system".
-- ============================================================
--changeset pohr:013-01
ALTER TABLE script_versions ADD COLUMN changed_by VARCHAR(64);

--changeset pohr:013-02
ALTER TABLE script_versions ADD COLUMN change_note VARCHAR(512);

--changeset pohr:013-03
ALTER TABLE scripts ADD COLUMN updated_by VARCHAR(64);

-- ============================================================
-- mcp_tokens — именные Bearer-токены для /mcp.
--
-- Храним только SHA-256 от токена: сам токен показывается админу
-- один раз при создании. token_prefix — первые символы для UI,
-- чтобы можно было отличить токены друг от друга.
-- ============================================================
--changeset pohr:013-04
CREATE TABLE mcp_tokens (
                            id            VARCHAR(36)   NOT NULL,
                            user_id       VARCHAR(36)   NOT NULL,
                            name          VARCHAR(64)   NOT NULL,
                            token_hash    VARCHAR(64)   NOT NULL,
                            token_prefix  VARCHAR(16)   NOT NULL,
                            created_at    VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                            last_used_at  VARCHAR(36),
                            revoked_at    VARCHAR(36),
                            PRIMARY KEY (id)
);

--changeset pohr:013-05
CREATE UNIQUE INDEX idx_mcp_tokens_hash ON mcp_tokens(token_hash);
CREATE INDEX idx_mcp_tokens_user ON mcp_tokens(user_id, created_at DESC);
