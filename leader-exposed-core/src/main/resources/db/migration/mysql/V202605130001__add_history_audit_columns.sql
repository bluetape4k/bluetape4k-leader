-- MySQL 8.0+: add audit columns to bluetape4k_leader_lock_history (Issue #50)
-- ALGORITHM=INSTANT avoids table rebuild on InnoDB (MySQL 8.0+, MariaDB 10.3+).
ALTER TABLE bluetape4k_leader_lock_history
    ADD COLUMN error_type     VARCHAR(255)  NULL AFTER duration_ms,
    ADD COLUMN error_message  VARCHAR(512)  NULL AFTER error_type,
    ADD COLUMN kind           VARCHAR(32)   NULL AFTER error_message,
    ADD COLUMN participant_id VARCHAR(255)  NULL AFTER kind,
    ADD COLUMN metadata       TEXT          NULL AFTER participant_id,
    ADD COLUMN slot_id        VARCHAR(255)  NULL AFTER metadata,
    ALGORITHM=INSTANT;
