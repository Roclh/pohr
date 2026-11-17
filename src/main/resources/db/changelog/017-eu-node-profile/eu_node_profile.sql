--liquibase formatted sql

--changeset roclh:017-eu-node-profile
ALTER TABLE eu_nodes ADD COLUMN sni VARCHAR(253);
ALTER TABLE eu_nodes ADD COLUMN target VARCHAR(255);
ALTER TABLE eu_nodes ADD COLUMN fingerprint VARCHAR(16);
ALTER TABLE eu_nodes ADD COLUMN profile_updated_at VARCHAR(36);

--changeset roclh:017-eu-node-profile-history
CREATE TABLE eu_node_profile_history (
                                        id          VARCHAR(36)  PRIMARY KEY NOT NULL,
                                        node_id     VARCHAR(36)  NOT NULL,
                                        sni         VARCHAR(253),
                                        target      VARCHAR(255),
                                        fingerprint VARCHAR(16),
                                        created_at  VARCHAR(32)  NOT NULL,
                                        created_by  VARCHAR(128)
);
CREATE INDEX idx_eu_node_profile_history_node ON eu_node_profile_history (node_id, created_at);
