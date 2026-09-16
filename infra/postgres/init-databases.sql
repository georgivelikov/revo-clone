-- Bootstrap script run once by the official postgres:17 image's
-- /docker-entrypoint-initdb.d mechanism, on first container start against an empty data
-- directory, as the bootstrap superuser. It creates one database per stateful service, each
-- owned by its own non-superuser role -- never a schema inside a shared database -- so the
-- same layout maps later to either eight schemas in one managed cluster or eight separate
-- instances, and that choice stays open. See plans/phase-00-foundations.md, Infrastructure.
--
-- Mirrors the two constraints managed Postgres (RDS/Aurora) imposes for real: no superuser
-- role is ever granted to a service, and every migration in this project runs as an ordinary
-- table owner from the very first one.
--
-- The passwords below are literal, fixed strings. That is deliberate and safe ONLY because
-- this script provisions the docker-compose.infra.yml local-dev stack exclusively -- it is
-- never deployed, and no service config reads a value from here (every service's own
-- application.yml takes its DB endpoint, username and password from environment variables,
-- per CLAUDE.md's config-hygiene rule; this file just has to agree with whatever
-- application-local.yml defaults later tasks set for local development).

CREATE ROLE identity     WITH LOGIN PASSWORD 'identity';
CREATE DATABASE identity     OWNER identity;

CREATE ROLE account      WITH LOGIN PASSWORD 'account';
CREATE DATABASE account      OWNER account;

CREATE ROLE ledger       WITH LOGIN PASSWORD 'ledger';
CREATE DATABASE ledger       OWNER ledger;

CREATE ROLE payment      WITH LOGIN PASSWORD 'payment';
CREATE DATABASE payment      OWNER payment;

CREATE ROLE fx           WITH LOGIN PASSWORD 'fx';
CREATE DATABASE fx           OWNER fx;

CREATE ROLE card         WITH LOGIN PASSWORD 'card';
CREATE DATABASE card         OWNER card;

CREATE ROLE analytics    WITH LOGIN PASSWORD 'analytics';
CREATE DATABASE analytics    OWNER analytics;

CREATE ROLE notification WITH LOGIN PASSWORD 'notification';
CREATE DATABASE notification OWNER notification;

-- No further grants: each role already owns exactly its own database and nothing else, so
-- it has no reachable path to any of the other seven. The gateway and simulator services are
-- deliberately absent here -- they own no state.
