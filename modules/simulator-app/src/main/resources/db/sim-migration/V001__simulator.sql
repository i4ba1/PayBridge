-- Simulator schema (DMR section 9), created and owned by the simulator account pb_sim in paybridge_sim.
-- Differences from the DMR text, all additive: sim_scenario_run gains max_uses and used_count (API contract 8.3 reports
-- usedCount and the EXHAUSTED state), sim_key_counter remembers the run a key was first counted under, and
-- sim_charge gets ix_sim_charge_updated for the operations listing by updatedAt (API contract 8.3).
CREATE TABLE sim_charge (
  id                 CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_key    VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash       CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_reference VARCHAR(40)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payment_id         CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_id        CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  invoice_id         CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_minor       BIGINT       NOT NULL,
  currency           CHAR(3)      NOT NULL,
  method_token       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scenario_run_id    VARCHAR(64)  CHARACTER SET ascii NOT NULL,
  status             ENUM('PENDING','SUCCEEDED','DECLINED') NOT NULL,
  decline_code       VARCHAR(40)  CHARACTER SET ascii NULL,
  revision           BIGINT       NOT NULL,
  planned_final      ENUM('SUCCEEDED','DECLINED') NULL,
  complete_at        DATETIME(6)  NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sim_charge_key (idempotency_key),
  UNIQUE KEY ux_sim_charge_reference (provider_reference),
  KEY ix_sim_charge_created (created_at, id),
  KEY ix_sim_charge_updated (updated_at, id),
  KEY ix_sim_charge_pending (status, complete_at)
) ENGINE=InnoDB;

CREATE TABLE sim_scenario_run (
  run_id      VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  description VARCHAR(200) NOT NULL DEFAULT '',
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  max_uses    INT          NULL,
  used_count  INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64)  NOT NULL,
  created_at  DATETIME(6)  NOT NULL,
  expires_at  DATETIME(6)  NULL,
  PRIMARY KEY (run_id),
  CONSTRAINT ck_sim_run_uses CHECK (max_uses IS NULL OR max_uses >= 1)
) ENGINE=InnoDB;

CREATE TABLE sim_scenario_rule (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  run_id             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  priority           INT          NOT NULL,
  match_method_token VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  match_currency     CHAR(3)      NULL,
  match_amount_minor BIGINT       NULL,
  behavior           ENUM('SUCCESS','DECLINE','DELAYED_TRANSFER','TIMEOUT_BEFORE_PROCESSING','SUCCESS_RESPONSE_LOST') NOT NULL,
  params             JSON         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sim_rule_priority (run_id, priority),
  CONSTRAINT fk_sim_rule_run FOREIGN KEY (run_id) REFERENCES sim_scenario_run (run_id)
) ENGINE=InnoDB;

-- counts charge requests per idempotency key so "fail the first N requests" is deterministic
CREATE TABLE sim_key_counter (
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scenario_run_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
  hits            INT         NOT NULL,
  first_seen_at   DATETIME(6) NOT NULL,
  last_seen_at    DATETIME(6) NOT NULL,
  PRIMARY KEY (idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE sim_webhook_delivery (
  id               CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  charge_id        CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id         CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type       VARCHAR(40)  CHARACTER SET ascii NOT NULL,
  revision         BIGINT       NOT NULL,
  body             JSON         NOT NULL,
  raw_body         MEDIUMTEXT   NOT NULL,
  state            ENUM('PENDING','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING',
  attempt_count    INT          NOT NULL DEFAULT 0,
  next_attempt_at  DATETIME(6)  NOT NULL,
  last_http_status SMALLINT     NULL,
  last_error       VARCHAR(255) NULL,
  created_at       DATETIME(6)  NOT NULL,
  delivered_at     DATETIME(6)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_sim_webhook_event (event_id),
  KEY ix_sim_webhook_due (state, next_attempt_at),
  CONSTRAINT fk_sim_webhook_charge FOREIGN KEY (charge_id) REFERENCES sim_charge (id)
) ENGINE=InnoDB;

-- built in run: always success, but transfers are confirmed after a short delay
INSERT INTO sim_scenario_run (run_id, description, active, created_by, created_at)
VALUES ('default', 'Built in run: every charge succeeds; transfers confirm after 3 seconds', 1, 'migration', UTC_TIMESTAMP(6));
INSERT INTO sim_scenario_rule (run_id, priority, match_method_token, behavior, params) VALUES
 ('default', 1000, 'tok_sim_transfer_default', 'DELAYED_TRANSFER', JSON_OBJECT('delaySeconds', 3, 'finalOutcome', 'SUCCEEDED')),
 ('default', 2000, NULL, 'SUCCESS', JSON_OBJECT());
