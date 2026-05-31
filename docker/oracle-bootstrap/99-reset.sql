-- Wipe and recreate the khmerbank schema. Run as SYSTEM in XEPDB1.
-- sqlplus system/123@localhost:1521/XEPDB1 @99-reset.sql

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
EXIT;
