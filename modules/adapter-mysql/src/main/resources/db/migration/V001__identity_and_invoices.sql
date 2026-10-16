-- PayBridge V001: identity, invoices, audit trail and method reference tables (DMR section 2).
-- Identifiers are CHAR(36) ASCII binary (decision D-27); timestamps are UTC DATETIME(6).
CREATE TABLE app_user (
  id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  username             VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name         VARCHAR(120) NOT NULL,
  password_hash        VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  global_role          ENUM('ADMIN','USER') NOT NULL DEFAULT 'USER',
  enabled              TINYINT(1)   NOT NULL DEFAULT 1,
  must_change_password TINYINT(1)   NOT NULL DEFAULT 0,
  password_changed_at  DATETIME(6)  NULL,
  created_at           DATETIME(6)  NOT NULL,
  updated_at           DATETIME(6)  NOT NULL,
  version              BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_app_user_username (username),
  CONSTRAINT ck_app_user_username CHECK (username REGEXP '^[a-z0-9][a-z0-9._-]{2,63}$')
) ENGINE=InnoDB;
 
CREATE TABLE merchant (
  id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  code       VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name       VARCHAR(120) NOT NULL,
  currency   CHAR(3)      NOT NULL,
  status     ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6)  NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  version    BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_merchant_code (code),
  CONSTRAINT ck_merchant_currency CHECK (currency IN ('IDR','USD'))
) ENGINE=InnoDB;
 
CREATE TABLE membership (
  id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role        ENUM('OPERATOR','VIEWER') NOT NULL,
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  version     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_membership_user_merchant (user_id, merchant_id),
  KEY ix_membership_merchant (merchant_id),
  CONSTRAINT fk_membership_user     FOREIGN KEY (user_id)     REFERENCES app_user (id),
  CONSTRAINT fk_membership_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE=InnoDB;
 
-- Singleton row (id = 1) created by the migrate role. Consumed exactly once.
CREATE TABLE bootstrap_state (
  id                  TINYINT     NOT NULL,
  state               ENUM('PENDING','CONSUMED','EXPIRED') NOT NULL,
  token_hash          CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NULL,
  token_expires_at    DATETIME(6) NULL,
  consumed_at         DATETIME(6) NULL,
  consumed_by_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at          DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_bootstrap_singleton CHECK (id = 1),
  CONSTRAINT fk_bootstrap_user FOREIGN KEY (consumed_by_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB;
 
CREATE TABLE invoice (
  id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reference         VARCHAR(40)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  description       VARCHAR(200) NOT NULL DEFAULT '',
  amount_minor      BIGINT       NOT NULL,
  currency          CHAR(3)      NOT NULL,
  active_payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at        DATETIME(6)  NOT NULL,
  version           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_invoice_merchant_reference (merchant_id, reference),
  KEY ix_invoice_merchant (merchant_id, id),
  CONSTRAINT ck_invoice_amount   CHECK (amount_minor > 0),
  CONSTRAINT ck_invoice_currency CHECK (currency IN ('IDR','USD')),
  CONSTRAINT fk_invoice_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE=InnoDB;
-- fk_invoice_active_payment is added in V002 after payment exists.
 
-- Immutable business audit trail. Application account has INSERT and SELECT only.
CREATE TABLE audit_action (
  id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at    DATETIME(6)  NOT NULL,
  actor_type     ENUM('USER','SERVICE','SYSTEM') NOT NULL,
  actor_user_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  merchant_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action         VARCHAR(64)  CHARACTER SET ascii NOT NULL,
  target_type    VARCHAR(32)  CHARACTER SET ascii NOT NULL,
  target_id      VARCHAR(64)  CHARACTER SET ascii NULL,
  result         ENUM('SUCCESS','REJECTED','FAILURE') NOT NULL,
  correlation_id VARCHAR(64)  CHARACTER SET ascii NOT NULL,
  details        JSON         NULL,
  PRIMARY KEY (id),
  KEY ix_audit_merchant_time (merchant_id, occurred_at, id),
  KEY ix_audit_action_time (action, occurred_at, id),
  KEY ix_audit_target (target_type, target_id),
  KEY ix_audit_actor (actor_user_id, occurred_at)
) ENGINE=InnoDB;
 
CREATE TABLE method_catalog (
  method_code   VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name  VARCHAR(80)  NOT NULL,
  settlement    ENUM('IMMEDIATE','DELAYED') NOT NULL,
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (method_code)
) ENGINE=InnoDB;
 
CREATE TABLE method_token (
  token         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  method_code   VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  description   VARCHAR(120) NOT NULL DEFAULT '',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (token),
  KEY ix_method_token_method (method_code),
  CONSTRAINT fk_method_token_method FOREIGN KEY (method_code) REFERENCES method_catalog (method_code)
) ENGINE=InnoDB;
 
CREATE TABLE merchant_method (
  merchant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  method_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled     TINYINT(1)  NOT NULL DEFAULT 1,
  PRIMARY KEY (merchant_id, method_code),
  CONSTRAINT fk_merchant_method_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id),
  CONSTRAINT fk_merchant_method_method   FOREIGN KEY (method_code) REFERENCES method_catalog (method_code)
) ENGINE=InnoDB;

