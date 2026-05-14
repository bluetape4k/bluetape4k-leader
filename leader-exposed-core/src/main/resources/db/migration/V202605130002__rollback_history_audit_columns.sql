-- PostgreSQL: rollback audit columns added in V202605130001 (Issue #50)
SET lock_timeout = '3s';

ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS error_type;
ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS error_message;
ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS kind;
ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS participant_id;
ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS metadata;
ALTER TABLE bluetape4k_leader_lock_history DROP COLUMN IF EXISTS slot_id;
