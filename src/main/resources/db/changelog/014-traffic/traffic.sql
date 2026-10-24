--liquibase formatted sql

-- ============================================================
-- Статистика трафика по клиентам.
--
-- Источник — счётчики Xray `user>>>{email}>>>traffic>>>{direction}`,
-- где email = id подписки (материализатор уже проставляет его).
--
-- node_id присутствует во всех таблицах с первого дня: сейчас он
-- равен значению pohr.node.id (по умолчанию "local"), а при переходе
-- на несколько RU-нод каждая нода будет писать свои строки со своим id.
-- ============================================================

--changeset pohr:014-01
CREATE TABLE client_traffic_state (
                                     id            INTEGER PRIMARY KEY AUTOINCREMENT,
                                     node_id       VARCHAR(64) NOT NULL,
                                     email         VARCHAR(64) NOT NULL,
                                     raw_uplink    INTEGER     NOT NULL,
                                     raw_downlink  INTEGER     NOT NULL,
                                     updated_at    VARCHAR(36) NOT NULL,
                                     CONSTRAINT uq_client_traffic_state UNIQUE (node_id, email)
);

--changeset pohr:014-02
CREATE TABLE client_traffic_samples (
                                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                                        ts              VARCHAR(36) NOT NULL,
                                        node_id         VARCHAR(64) NOT NULL,
                                        user_id         VARCHAR(36),
                                        subscription_id VARCHAR(36),
                                        email           VARCHAR(64) NOT NULL,
                                        uplink_bytes    INTEGER     NOT NULL DEFAULT 0,
                                        downlink_bytes  INTEGER     NOT NULL DEFAULT 0
);

--changeset pohr:014-03
CREATE INDEX idx_cts_ts ON client_traffic_samples (ts);
CREATE INDEX idx_cts_user_ts ON client_traffic_samples (user_id, ts);
CREATE INDEX idx_cts_node_ts ON client_traffic_samples (node_id, ts);

--changeset pohr:014-04
CREATE TABLE client_traffic_daily (
                                      id             INTEGER PRIMARY KEY AUTOINCREMENT,
                                      day            VARCHAR(10) NOT NULL,
                                      node_id        VARCHAR(64) NOT NULL,
                                      user_id        VARCHAR(36) NOT NULL,
                                      uplink_bytes   INTEGER     NOT NULL DEFAULT 0,
                                      downlink_bytes INTEGER     NOT NULL DEFAULT 0,
                                      last_seen_at   VARCHAR(36) NOT NULL,
                                      CONSTRAINT uq_client_traffic_daily UNIQUE (day, node_id, user_id)
);

--changeset pohr:014-05
CREATE INDEX idx_ctd_day ON client_traffic_daily (day);
CREATE INDEX idx_ctd_user_day ON client_traffic_daily (user_id, day);

-- ============================================================
-- Агрегат по ноде за интервал: активные TCP-соединения к Xray,
-- число клиентов с трафиком и суммарные байты. Нужен для оценки
-- «сколько потоков держит нода» без хранения per-flow строк.
-- ============================================================
--changeset pohr:014-06
CREATE TABLE node_traffic_samples (
                                      id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                                      ts                 VARCHAR(36) NOT NULL,
                                      node_id            VARCHAR(64) NOT NULL,
                                      active_connections INTEGER,
                                      active_users       INTEGER,
                                      uplink_bytes       INTEGER     NOT NULL DEFAULT 0,
                                      downlink_bytes     INTEGER     NOT NULL DEFAULT 0,
                                      xray_available     INTEGER     NOT NULL DEFAULT 0
);

--changeset pohr:014-07
CREATE INDEX idx_nts_node_ts ON node_traffic_samples (node_id, ts);
