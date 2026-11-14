--liquibase formatted sql

-- ============================================================
-- Честное давление на память.
--
-- memory.current включает page cache: файловый кэш SQLite, страницы
-- бинарников, WAL. Ядро вытесняет его под давлением, поэтому «занято
-- 480 из 480» не означает, что процессам не хватает памяти.
-- Колонка pod_anon_bytes хранит anon из memory.stat — то, что нельзя
-- освободить без свопа. Диагностика и графики должны опираться на неё,
-- а pod_mem_bytes остаётся для контроля лимита.
-- ============================================================

--changeset pohr:015-01
ALTER TABLE metric_samples ADD COLUMN pod_anon_bytes INTEGER;
--rollback ALTER TABLE metric_samples DROP COLUMN pod_anon_bytes;
