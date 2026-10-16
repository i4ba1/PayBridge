-- Table level grants for the application accounts (DMR section 2, grant listing).
-- Applied by the migrate role right after Flyway, as pb_migrate (which holds GRANT OPTION on paybridge.* only).
-- MySQL cannot partially revoke a schema level grant, so every table is granted explicitly. A migration that adds a
-- table must add its grants here in the same change: TableGrantsIT fails when a table has no pb_core grant.
-- One statement per line; lines starting with "--" are comments. GRANT is idempotent.

GRANT SELECT ON paybridge.flyway_schema_history TO 'pb_core'@'%';

GRANT SELECT, INSERT, UPDATE ON paybridge.app_user        TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.merchant        TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.membership      TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.bootstrap_state TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.invoice         TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.method_catalog  TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.method_token    TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.merchant_method TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.payment         TO 'pb_core'@'%';
GRANT SELECT, INSERT         ON paybridge.payment_request TO 'pb_core'@'%';
GRANT SELECT, INSERT, UPDATE ON paybridge.payment_attempt TO 'pb_core'@'%';
GRANT SELECT, INSERT         ON paybridge.audit_action    TO 'pb_core'@'%';
