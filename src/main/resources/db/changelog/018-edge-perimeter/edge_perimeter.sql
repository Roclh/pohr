--liquibase formatted sql

--changeset roclh:018-edge-perimeter
-- Сайт, который проксировал порт панели, отдаёт форму логина «Pohr» на валидном
-- сертификате — это самый заметный внешний признак узла. Переводим apex на
-- статический сайт-легенду.
UPDATE edge_sites
   SET site_type       = 'static',
       root_path       = COALESCE(NULLIF(root_path, ''), '/app/xray/site'),
       upstream_host   = NULL,
       upstream_port   = NULL,
       redirect_target = NULL,
       redirect_code   = NULL,
       bind_address    = NULL,
       updated_at      = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
 WHERE site_type = 'proxy'
   AND upstream_port = 8080
   AND lower(upstream_host) IN ('127.0.0.1', 'localhost', '0.0.0.0', '::1', '[::1]');

-- Панель не убираем: у неё появляется свой хост. Пользователи входят сюда для
-- логина, регистрации по инвайту и получения ссылок подписки, админ — в /admin.
-- Значение должно совпадать с pohr.panel-url (POHR_PANEL_URL); для форка
-- поправить домен в обоих местах. Публикуется только этот хост — случайное
-- проксирование порта панели другим сайтом генератор Caddy запирает на loopback.
INSERT INTO edge_sites (id, domain, site_type, upstream_host, upstream_port,
                        bind_address, redirect_target, redirect_code, tls_mode,
                        enabled, updated_at)
SELECT '5e0f0a00-0000-4000-8000-00000000a11c',
       'panel.pohr-roclh.xyz', 'proxy',
       '127.0.0.1', 8080,
       NULL, NULL, NULL, 'acme', 1,
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
 WHERE NOT EXISTS (SELECT 1 FROM edge_sites WHERE domain = 'panel.pohr-roclh.xyz');

--rollback UPDATE edge_sites SET site_type = 'proxy', upstream_host = '127.0.0.1', upstream_port = 8080, bind_address = '127.0.0.1' WHERE domain = 'pohr-roclh.xyz' AND site_type = 'static';
--rollback DELETE FROM edge_sites WHERE domain = 'panel.pohr-roclh.xyz';
