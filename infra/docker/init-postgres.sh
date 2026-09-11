#!/bin/bash
# Postgres init script - runs once on first container boot.
# Creates a SEPARATE database for each Postgres-backed service so that no
# service shares a schema with another (NFR-1). Each service connects only to
# its own database.
set -e
set -u

for db in case_db hold_db export_db audit_db; do
  echo ">>> Creating database ${db}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE "$db";
    GRANT ALL PRIVILEGES ON DATABASE "$db" TO "$POSTGRES_USER";
EOSQL
done

echo ">>> All service databases created."
