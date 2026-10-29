# PostgreSQL data store

Replaces the Db2 objects created by `../db2jcl/INSTDB2.jcl` (database `CBSA`,
storage group, `ACCOUNT`, `PROCTRAN`, `CONTROL` and their indexes). Column names,
lengths, nullability and index keys are unchanged; `DECIMAL` becomes `NUMERIC`.

| File | Purpose |
| --- | --- |
| `00_create_database.sql` | Creates the `cbsa` login role and `cbsa` database (run as a superuser) |
| `01_create_tables.sql` | Creates `ACCOUNT` (`ACCTINDX`, `ACCTCUST`), `PROCTRAN`, `CONTROL` (`CONTINDX`) |
| `99_drop_tables.sql` | Drops the three tables |
| `migrate/MigrateDb2ToPostgres.java` | Copies existing Db2 rows into PostgreSQL and verifies them |

## Provision

```sh
psql -U postgres -v cbsa_password='<password>' -f 00_create_database.sql
psql -U cbsa -d cbsa -v ON_ERROR_STOP=1 -f 01_create_tables.sql
```

This replaces running `INSTDB2.jcl`, `CREDB2L.jcl` and the Db2 bind steps.

## Connect the web UI

The `webui` WAR looks up `jdbc/defaultCICSDataSource`. In the Liberty JVM server
`server.xml`, point it at PostgreSQL instead of `properties.db2.jcc`:

```xml
<library id="postgresLib">
    <file name="${server.config.dir}/lib/postgresql-42.7.4.jar"/>
</library>
<jdbcDriver id="postgresDriver" libraryRef="postgresLib"/>
<dataSource id="defaultCICSDataSource" jndiName="jdbc/defaultCICSDataSource"
            jdbcDriverRef="postgresDriver" transactional="false">
    <properties.postgresql serverName="pg.example.com" portNumber="5432"
                           databaseName="cbsa" user="cbsa" password="${env.CBSA_DB_PASSWORD}"/>
</dataSource>
```

Outside CICS (tests, local runs) the web tier opens connections itself when
`CBSA_JDBC_URL`, `CBSA_JDBC_USER` and `CBSA_JDBC_PASSWORD` are set as system
properties or environment variables.

## Migrate existing Db2 data

```sh
DB2_JDBC_URL=jdbc:db2://host:446/LOCATION DB2_USER=... DB2_PASSWORD=... [DB2_SCHEMA=IBMUSER] \
PG_JDBC_URL=jdbc:postgresql://host:5432/cbsa PG_USER=cbsa PG_PASSWORD=... \
java -cp db2jcc4.jar:postgresql.jar migrate/MigrateDb2ToPostgres.java [--verify-only]
```

Rows are copied over JDBC with `CHAR` values untrimmed (the fixed-offset
`PROCTRAN_DESC` layout is preserved), `DECIMAL` as `BigDecimal` (scale kept) and
`DATE` as `java.sql.Date`. Afterwards row counts and an order-independent
SHA-256 of every table are compared; the tool exits non-zero on a mismatch.
Run it with the CICS regions quiesced.

## A/B check against Db2

`etc/ab-test/run-ab.sh` runs the same scenario through the original Db2 SQL and the
PostgreSQL SQL, migrates the Db2 result with the tool above, and writes an HTML
side-by-side report. Needs Docker, Maven and Java 17.
