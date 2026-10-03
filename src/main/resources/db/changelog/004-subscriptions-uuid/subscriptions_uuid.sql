--liquibase formatted sql

--changeset roclh:004-subscriptions-uuid
ALTER TABLE subscriptions ADD COLUMN xray_uuid VARCHAR(36);

--changeset roclh:004-subscriptions-uuid-backfill
UPDATE subscriptions
SET xray_uuid = lower(
        hex(randomblob(4)) || '-' ||
        hex(randomblob(2)) || '-4' ||
        substr(hex(randomblob(2)), 2) || '-' ||
        substr('89ab', abs(random()) % 4 + 1, 1) ||
        substr(hex(randomblob(2)), 2) || '-' ||
        hex(randomblob(6))
                )
WHERE xray_uuid IS NULL;

--changeset roclh:004-subscriptions-uuid-unique
CREATE UNIQUE INDEX idx_subscriptions_xray_uuid ON subscriptions (xray_uuid);