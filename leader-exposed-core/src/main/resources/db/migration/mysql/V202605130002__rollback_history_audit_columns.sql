-- MySQL 8.0+: rollback audit columns added in V202605130001 (Issue #50)
ALTER TABLE bluetape4k_leader_lock_history
    DROP COLUMN error_type,
    DROP COLUMN error_message,
    DROP COLUMN kind,
    DROP COLUMN participant_id,
    DROP COLUMN metadata,
    DROP COLUMN slot_id,
    ALGORITHM=INSTANT;
