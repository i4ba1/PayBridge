-- PayBridge V004: support notes, merchant quotas, dead letters and cluster wide flags (DMR section 2).
CREATE TABLE reconciliation_note (
  id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  author_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind           ENUM('NOTE','DISPOSITION') NOT NULL DEFAULT 'NOTE',
  note           VARCHAR(1000) NOT NULL,
  correlation_id VARCHAR(64)   CHARACTER SET ascii NOT NULL,
  created_at     DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  KEY ix_note_case (case_id, created_at, id),
  CONSTRAINT fk_note_case FOREIGN KEY (case_id) REFERENCES reconciliation_case (id),
  CONSTRAINT fk_note_user FOREIGN KEY (author_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB;

CREATE TABLE merchant_quota_setting (
  merchant_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rate_per_second    INT         NOT NULL DEFAULT 20,
  burst_capacity     INT         NOT NULL DEFAULT 40,
  updated_by         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  updated_at         DATETIME(6) NOT NULL,
  version            BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (merchant_id),
  CONSTRAINT ck_quota_rate  CHECK (rate_per_second BETWEEN 1 AND 1000),
  CONSTRAINT ck_quota_burst CHECK (burst_capacity >= rate_per_second AND burst_capacity <= 5000),
  CONSTRAINT fk_quota_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE=InnoDB;

CREATE TABLE dead_letter_record (
  id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  queue_name       VARCHAR(80)  CHARACTER SET ascii NOT NULL,
  message_key      VARCHAR(80)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payment_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  failure_class    ENUM('MALFORMED_PAYLOAD','SCHEMA_UNSUPPORTED','INVARIANT_VIOLATION') NOT NULL,
  failure_count    INT          NOT NULL,
  last_error       VARCHAR(500) NOT NULL,
  headers          JSON         NOT NULL,
  body_sha256      CHAR(64)     CHARACTER SET ascii NOT NULL,
  body_sanitized   JSON         NULL,
  state            ENUM('OPEN','REPLAYED','DISCARDED') NOT NULL DEFAULT 'OPEN',
  first_failed_at  DATETIME(6)  NOT NULL,
  last_failed_at   DATETIME(6)  NOT NULL,
  replay_count     INT          NOT NULL DEFAULT 0,
  last_replayed_at DATETIME(6)  NULL,
  last_replayed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_dead_letter_message (queue_name, message_key),
  KEY ix_dead_letter_state (state, last_failed_at)
) ENGINE=InnoDB;

-- Cluster wide switches read by every API pod (cached in process for 1 second).
CREATE TABLE system_flag (
  flag_key   VARCHAR(64)  CHARACTER SET ascii NOT NULL,
  flag_value VARCHAR(255) NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  updated_by VARCHAR(80)  NOT NULL,
  PRIMARY KEY (flag_key)
) ENGINE=InnoDB;

INSERT INTO system_flag VALUES
  ('admission.capacity',    'OPEN', UTC_TIMESTAMP(6), 'migration'),
  ('admission.maintenance', 'OFF',  UTC_TIMESTAMP(6), 'migration');
