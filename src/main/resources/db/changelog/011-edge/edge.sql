--liquibase formatted sql

--changeset roclh:011-edge

CREATE TABLE edge_config (
                             name                       VARCHAR(32)  NOT NULL PRIMARY KEY,
                             stream_port                INTEGER      NOT NULL,
                             http_port                  INTEGER      NOT NULL,
                             https_port                 INTEGER      NOT NULL,
                             fallback_target            VARCHAR(255) NOT NULL,
                             worker_processes           VARCHAR(16)  NOT NULL DEFAULT 'auto',
                             worker_connections         INTEGER      NOT NULL DEFAULT 768,
                             stream_proxy_timeout       VARCHAR(16)  NOT NULL DEFAULT '300s',
                             stream_connect_timeout     VARCHAR(16)  NOT NULL DEFAULT '5s',
                             auto_https                 VARCHAR(32)  NOT NULL DEFAULT 'disable_redirects',
                             extra_stream_directives    TEXT,
                             extra_http_directives      TEXT,
                             extra_global_directives    TEXT,
                             updated_at                 VARCHAR(36)  NOT NULL
);

CREATE TABLE edge_routes (
                             id            VARCHAR(36)  NOT NULL PRIMARY KEY,
                             sni           VARCHAR(253) NOT NULL UNIQUE,
                             target_host   VARCHAR(255) NOT NULL,
                             target_port   INTEGER      NOT NULL,
                             protocol      VARCHAR(16)  NOT NULL DEFAULT 'tcp',
                             description   VARCHAR(255),
                             enabled       INTEGER      NOT NULL DEFAULT 1,
                             sort_order    INTEGER      NOT NULL DEFAULT 0,
                             updated_at    VARCHAR(36)  NOT NULL
);

CREATE INDEX idx_edge_routes_sort ON edge_routes(sort_order, sni);

CREATE TABLE edge_sites (
                            id                VARCHAR(36)  NOT NULL PRIMARY KEY,
                            domain            VARCHAR(253) NOT NULL UNIQUE,
                            site_type         VARCHAR(16)  NOT NULL DEFAULT 'proxy',
                            upstream_host     VARCHAR(255),
                            upstream_port     INTEGER,
                            bind_address      VARCHAR(64),
                            redirect_target   VARCHAR(512),
                            redirect_code     VARCHAR(32),
                            tls_mode          VARCHAR(16)  NOT NULL DEFAULT 'acme',
                            acme_email        VARCHAR(255),
                            extra_directives  TEXT,
                            enabled           INTEGER      NOT NULL DEFAULT 1,
                            updated_at        VARCHAR(36)  NOT NULL
);

INSERT INTO edge_config(name, stream_port, http_port, https_port,
                        fallback_target, worker_processes, worker_connections,
                        stream_proxy_timeout, stream_connect_timeout,
                        auto_https, updated_at)
VALUES ('default', 443, 80, 8444,
        '127.0.0.1:8444', 'auto', 768,
        '300s', '5s',
        'disable_redirects',
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));