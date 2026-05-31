# Running KhmerBank on Oracle Database

The gateway supports **PostgreSQL** (default) and **Oracle 19c / 21c / 23ai**
through Spring profiles.

## TL;DR

```bash
SPRING_PROFILES_ACTIVE=oracle \
DB_URL=jdbc:oracle:thin:@//your-host:1521/YOUR_PDB \
DB_USER=KHMERBANK \
DB_PASS=secret \
mvn spring-boot:run
```

Flyway will pick up `db/migration/oracle/V1__init_schema.sql` automatically and create:
`users · subscriptions · api_keys · merchants · qr_codes · transactions · webhooks`.

## Local docker-compose

```bash
docker compose -f docker/docker-compose.oracle.yml up -d
```

Boots:
- `oracle` (Oracle Database 23ai Free, ~5 min first start)
- `redis`
- `server` (Spring Boot, profile `oracle`)
- `dashboard` (React on Nginx)

The bootstrap script in `docker/oracle-bootstrap/01-create-user.sql`
creates the `khmerbank` schema/user inside `FREEPDB1`.

## Oracle Cloud (Autonomous Database)

1. Download your **Wallet** from OCI console.
2. Unzip somewhere readable, e.g. `/opt/oracle/wallet`.
3. Set:

```bash
SPRING_PROFILES_ACTIVE=oracle
DB_URL=jdbc:oracle:thin:@your_db_high?TNS_ADMIN=/opt/oracle/wallet
DB_USER=KHMERBANK
DB_PASS=YourLongPassword#2026
```

`oracle.net.tns_admin` is read from the URL, no extra Hibernate config required.

## Schema notes

| Concern         | Postgres                         | Oracle (this build)                   |
| --------------- | -------------------------------- | ------------------------------------- |
| UUID storage    | `UUID` native                    | `RAW(16)` (Hibernate handles binding) |
| Boolean         | `BOOLEAN`                        | `NUMBER(1)` 0/1                       |
| Timestamps      | `TIMESTAMPTZ`                    | `TIMESTAMP WITH TIME ZONE`            |
| Auto-PK         | `gen_random_uuid()`              | App-side `GenerationType.UUID`        |
| Large QR image  | `TEXT`                           | `CLOB`                                |
| `now()`         | `NOW()`                          | `SYSTIMESTAMP`                        |

The JPA entities are vendor-neutral: `@GeneratedValue(strategy = GenerationType.UUID)`
generates the UUID in Java, and Hibernate persists it as `RAW(16)` on Oracle and `UUID` on Postgres.

## Switching back to Postgres

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_URL=jdbc:postgresql://host:5432/khmerbank ...
```
