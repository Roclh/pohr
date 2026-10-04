--liquibase formatted sql

--changeset pohr:008-metrics
CREATE TABLE metric_samples (
                                id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                                ts                    VARCHAR(36) NOT NULL,
                                pod_cpu_pct           REAL,
                                pod_mem_bytes         INTEGER,
                                pod_mem_limit_bytes   INTEGER,
                                jvm_heap_bytes        INTEGER,
                                jvm_threads           INTEGER,
                                xray_running          INTEGER NOT NULL DEFAULT 0,
                                xray_cpu_pct          REAL,
                                xray_mem_bytes        INTEGER,
                                xray_rx_bytes         INTEGER,
                                xray_tx_bytes         INTEGER
);

CREATE INDEX idx_metric_samples_ts ON metric_samples (ts);
--rollback DROP TABLE metric_samples;