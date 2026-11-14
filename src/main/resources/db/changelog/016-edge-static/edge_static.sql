--liquibase formatted sql

--changeset roclh:016-edge-static
-- Статические сайты Caddy: каталог с файлами (placeholder и будущий сайт Roclh).
ALTER TABLE edge_sites ADD COLUMN root_path VARCHAR(512);
