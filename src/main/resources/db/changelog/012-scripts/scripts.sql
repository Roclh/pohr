--liquibase formatted sql

-- ============================================================
-- scripts — реестр всех скриптов
-- ============================================================
--changeset pohr:012-01
CREATE TABLE scripts (
                         id              VARCHAR(36)   NOT NULL,
                         name            VARCHAR(128)  NOT NULL,
                         display_name    VARCHAR(128)  NOT NULL,
                         description     VARCHAR(1024),
                         platform        VARCHAR(32)   NOT NULL DEFAULT 'any',
                         access_level    VARCHAR(16)   NOT NULL DEFAULT 'ADMIN',
                         is_entrypoint   INTEGER       NOT NULL DEFAULT 0,
                         sort_order      INTEGER       NOT NULL DEFAULT 1000,
                         enabled         INTEGER       NOT NULL DEFAULT 1,
                         is_seeded       INTEGER       NOT NULL DEFAULT 0,
                         seed_version    VARCHAR(32),
                         created_at      VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                         updated_at      VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                         PRIMARY KEY (id)
);

--changeset pohr:012-02
CREATE UNIQUE INDEX idx_scripts_name ON scripts(name);
CREATE INDEX idx_scripts_entrypoint ON scripts(is_entrypoint, enabled);
CREATE INDEX idx_scripts_access ON scripts(access_level, enabled);

-- ============================================================
-- script_versions — история версий каждого скрипта
-- ============================================================
--changeset pohr:012-03
CREATE TABLE script_versions (
                                 id              VARCHAR(36)   NOT NULL,
                                 script_id       VARCHAR(36)   NOT NULL,
                                 version         VARCHAR(32)   NOT NULL,
                                 version_major   INTEGER       NOT NULL,
                                 version_minor   INTEGER       NOT NULL,
                                 hash            VARCHAR(64)   NOT NULL,
                                 size            INTEGER       NOT NULL,
                                 source          VARCHAR(32)   NOT NULL,
                                 snapshot_path   VARCHAR(512),
                                 restore_of      VARCHAR(36),
                                 created_at      VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                                 invalidated_at  VARCHAR(36),
                                 PRIMARY KEY (id)
);

--changeset pohr:012-04
CREATE INDEX idx_script_versions_script ON script_versions(script_id, invalidated_at);
CREATE INDEX idx_script_versions_hash ON script_versions(hash);
CREATE INDEX idx_script_versions_version ON script_versions(script_id, version_major DESC, version_minor DESC);

-- ============================================================
-- script_downloads — лог скачиваний /raw (нужен для fallback'а
-- и аналитики). Ротация: 1000 записей на юзера или 2 недели.
-- ============================================================
--changeset pohr:012-05
CREATE TABLE script_downloads (
                                  id              VARCHAR(36)   NOT NULL,
                                  user_id         VARCHAR(36)   NOT NULL,
                                  script_id       VARCHAR(36)   NOT NULL,
                                  script_version  VARCHAR(32)   NOT NULL,
                                  client_ip       VARCHAR(64),
                                  user_agent      VARCHAR(512),
                                  downloaded_at   VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                                  PRIMARY KEY (id)
);

--changeset pohr:012-06
CREATE INDEX idx_script_downloads_user ON script_downloads(user_id, downloaded_at DESC);
CREATE INDEX idx_script_downloads_script ON script_downloads(script_id, downloaded_at DESC);
CREATE INDEX idx_script_downloads_time ON script_downloads(downloaded_at);

-- ============================================================
-- user_script_versions — текущее состояние (upsert).
-- PK составной, чтобы lookup на /home был O(1).
-- ============================================================
--changeset pohr:012-07
CREATE TABLE user_script_versions (
                                      user_id         VARCHAR(36)   NOT NULL,
                                      script_id       VARCHAR(36)   NOT NULL,
                                      version         VARCHAR(32)   NOT NULL,
                                      version_major   INTEGER       NOT NULL,
                                      version_minor   INTEGER       NOT NULL,
                                      reported_at     VARCHAR(36)   NOT NULL,
                                      client_ip       VARCHAR(64),
                                      user_agent      VARCHAR(512),
                                      PRIMARY KEY (user_id, script_id)
);

--changeset pohr:012-08
CREATE INDEX idx_user_script_versions_script ON user_script_versions(script_id, version);

-- ============================================================
-- script_version_reports — append-only история sync'ов.
-- Ротация: 1000 на юзера или 2 недели.
-- ============================================================
--changeset pohr:012-09
CREATE TABLE script_version_reports (
                                        id              VARCHAR(36)   NOT NULL,
                                        user_id         VARCHAR(36)   NOT NULL,
                                        script_id       VARCHAR(36)   NOT NULL,
                                        version         VARCHAR(32)   NOT NULL,
                                        root_script_id  VARCHAR(36),
                                        client_ip       VARCHAR(64),
                                        user_agent      VARCHAR(512),
                                        reported_at     VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                                        PRIMARY KEY (id)
);

--changeset pohr:012-10
CREATE INDEX idx_script_version_reports_user ON script_version_reports(user_id, reported_at DESC);
CREATE INDEX idx_script_version_reports_script ON script_version_reports(script_id, reported_at DESC);
CREATE INDEX idx_script_version_reports_time ON script_version_reports(reported_at);

-- ============================================================
-- script_error_reports — отчёты об ошибках от клиентов.
-- Plaintext. Удаляются при resolved. Retention: 30 дней
-- для неразобранных (агрегаты в daily_stats — отдельно).
-- ============================================================
--changeset pohr:012-11
CREATE TABLE script_error_reports (
                                      id                  VARCHAR(36)   NOT NULL,
                                      user_id             VARCHAR(36)   NOT NULL,
                                      root_script_id      VARCHAR(36),
                                      root_script_version VARCHAR(32),
                                      stage               VARCHAR(64),
                                      status              VARCHAR(16)   NOT NULL DEFAULT 'new',
                                      report_text         TEXT          NOT NULL,
                                      client_ip           VARCHAR(64),
                                      user_agent          VARCHAR(512),
                                      received_at         VARCHAR(36)   NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                                      resolved_at         VARCHAR(36),
                                      resolved_by         VARCHAR(36),
                                      PRIMARY KEY (id)
);

--changeset pohr:012-12
CREATE INDEX idx_script_error_reports_status ON script_error_reports(status, received_at DESC);
CREATE INDEX idx_script_error_reports_user ON script_error_reports(user_id, received_at DESC);
CREATE INDEX idx_script_error_reports_script ON script_error_reports(root_script_id, received_at DESC);
CREATE INDEX idx_script_error_reports_time ON script_error_reports(received_at);

-- ============================================================
-- script_daily_stats — дневные агрегаты для аналитики
-- (после удаления самих отчётов).
-- ============================================================
--changeset pohr:012-13
CREATE TABLE script_daily_stats (
                                    day             VARCHAR(10)   NOT NULL,
                                    script_id       VARCHAR(36)   NOT NULL,
                                    script_version  VARCHAR(32)   NOT NULL,
                                    errors_count    INTEGER       NOT NULL DEFAULT 0,
                                    syncs_count     INTEGER       NOT NULL DEFAULT 0,
                                    downloads_count INTEGER       NOT NULL DEFAULT 0,
                                    PRIMARY KEY (day, script_id, script_version)
);

--changeset pohr:012-14
CREATE INDEX idx_script_daily_stats_script ON script_daily_stats(script_id, day DESC);