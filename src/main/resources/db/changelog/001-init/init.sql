--liquibase formatted sql

--changeset Roclh:001-create-users-table
CREATE TABLE users
(
    id            VARCHAR  PRIMARY KEY,
    username      VARCHAR  NOT NULL UNIQUE,
    password_hash VARCHAR NOT NULL,
    role          VARCHAR  NOT NULL,
    enabled       INTEGER      NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL
);
--rollback DROP TABLE users;