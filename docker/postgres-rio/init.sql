-- Makes the local rio database CDC-ready for Debezium (#115). The postgres image runs this file from
-- /docker-entrypoint-initdb.d/ once, when the data directory is empty, as POSTGRES_USER (rio, a
-- superuser) connected to POSTGRES_DB (rio). A volume initialized without it gets none of this until
-- it is reset (README, "Reset the database").
--
-- No tables here: db/SchemaInitializer.kt in the backend is the only owner of table DDL and creates
-- them on the backend's first start, after this file has run.

-- Local playground credentials only, like rio/rio in start.sh; the port is bound to 127.0.0.1.
CREATE ROLE rio_cdc WITH LOGIN REPLICATION PASSWORD 'rio_cdc';

GRANT CONNECT ON DATABASE rio TO rio_cdc;
GRANT USAGE ON SCHEMA public TO rio_cdc;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO rio_cdc;
-- The backend connects as rio and creates its tables later, so grant on what rio creates from now on.
ALTER DEFAULT PRIVILEGES FOR ROLE rio IN SCHEMA public GRANT SELECT ON TABLES TO rio_cdc;

-- Schema-wide, so it covers card_transactions and every other table the backend creates later.
-- Which of them reach Kafka is the connector's table.include.list, not this publication.
CREATE PUBLICATION rio_publication FOR TABLES IN SCHEMA public;
