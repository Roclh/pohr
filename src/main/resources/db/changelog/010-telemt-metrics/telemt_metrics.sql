--liquibase formatted sql

--changeset roclh:010-telemt-metrics
ALTER TABLE metric_samples ADD COLUMN telemt_running INTEGER NOT NULL DEFAULT 0;
--rollback ALTER TABLE metric_samples DROP COLUMN telemt_running;

--changeset roclh:010-telemt-cpu
ALTER TABLE metric_samples ADD COLUMN telemt_cpu_pct REAL;
--rollback ALTER TABLE metric_samples DROP COLUMN telemt_cpu_pct;

--changeset roclh:010-telemt-mem
ALTER TABLE metric_samples ADD COLUMN telemt_mem_bytes INTEGER;
--rollback ALTER TABLE metric_samples DROP COLUMN telemt_mem_bytes;

--changeset roclh:010-telemt-rx
ALTER TABLE metric_samples ADD COLUMN telemt_rx_bytes INTEGER;
--rollback ALTER TABLE metric_samples DROP COLUMN telemt_rx_bytes;

--changeset roclh:010-telemt-tx
ALTER TABLE metric_samples ADD COLUMN telemt_tx_bytes INTEGER;
--rollback ALTER TABLE metric_samples DROP COLUMN telemt_tx_bytes;