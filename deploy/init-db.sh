#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=resource_password="$RESOURCE_DB_PASSWORD" --set=workflow_password="$WORKFLOW_DB_PASSWORD" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE ROLE campus_resource LOGIN PASSWORD %L', :'resource_password') \gexec
SELECT format('CREATE ROLE campus_workflow LOGIN PASSWORD %L', :'workflow_password') \gexec
CREATE SCHEMA resource AUTHORIZATION campus_resource;
CREATE SCHEMA workflow AUTHORIZATION campus_workflow;
REVOKE ALL ON SCHEMA resource FROM PUBLIC;
REVOKE ALL ON SCHEMA workflow FROM PUBLIC;
ALTER ROLE campus_resource SET search_path TO resource;
ALTER ROLE campus_workflow SET search_path TO workflow;
SQL
