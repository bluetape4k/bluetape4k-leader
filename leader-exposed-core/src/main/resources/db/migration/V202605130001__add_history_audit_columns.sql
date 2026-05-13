-- PostgreSQL: add audit columns to bluetape4k_leader_lock_history (Issue #50)
-- These columns are all nullable so existing rows remain valid (additive migration).
-- Run with lock_timeout to avoid long-held AccessExclusiveLock on PostgreSQL < 17.
SET lock_timeout = '3s';

ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS error_type     VARCHAR(255);
ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS error_message  VARCHAR(512);
ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS kind           VARCHAR(32);
ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS participant_id VARCHAR(255);
ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS metadata       TEXT;
ALTER TABLE bluetape4k_leader_lock_history ADD COLUMN IF NOT EXISTS slot_id        VARCHAR(255);
