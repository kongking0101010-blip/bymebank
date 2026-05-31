-- Bootstrap khmerbank schema inside the XEPDB1 pluggable database.
-- Run as SYSTEM:  sqlplus system/123@localhost:1521/XEPDB1 @02-create-khmerbank.sql

-- Drop if already exists (idempotent).
DECLARE
    e_user_not_found EXCEPTION;
    PRAGMA EXCEPTION_INIT (e_user_not_found, -01918);
BEGIN
    EXECUTE IMMEDIATE 'DROP USER khmerbank CASCADE';
EXCEPTION
    WHEN e_user_not_found THEN NULL;
END;
/

CREATE USER khmerbank IDENTIFIED BY khmerbank;

GRANT CONNECT, RESOURCE TO khmerbank;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE,
      CREATE VIEW, CREATE PROCEDURE, CREATE TRIGGER TO khmerbank;
ALTER USER khmerbank QUOTA UNLIMITED ON USERS;

-- Friendly check
SELECT username, account_status, default_tablespace
FROM all_users WHERE username = 'KHMERBANK';

EXIT;
