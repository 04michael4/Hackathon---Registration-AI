#!/usr/bin/env bash
# Builds a private, local-only PostgreSQL database for the course catalog and verifies it.
#
#   data/setup-local-database.sh [--port N] [--dir DIR] [--full-validation]
#
# What it does (safe to rerun; it never drops or modifies existing data):
#   1. Finds PostgreSQL 15+ binaries (PG_BIN, pg_config, Homebrew, or /usr/lib/postgresql).
#   2. Creates a private cluster in DIR (default ~/.registration-local-pg, OUTSIDE the repository), listening on
#      127.0.0.1 only (TCP; no Unix socket), scram-sha-256, with random passwords kept ONLY in DIR/pgpass (mode 600). Passwords are never printed.
#   3. Creates database registration_local, an owner role (registration_app) and a read-only role for the backend
#      (registration_reader: SELECT on the catalog_api views and nothing else).
#   4. Loads, in order: data/cse007-snapshot/database.sql (David's verified CSE 007 schema and seed, unchanged),
#      data/catalog-api (read-only views), data/banner-evidence (schema + the seven user-reported links).
#   5. Runs the verification: David's verify.sql, the catalog_api readiness tests, the banner_evidence probes and the
#      collector unit tests. With --full-validation it also runs data/banner-evidence/run_validation.py (installs,
#      reimports, mutation checks; a few minutes).
#
# No Banner records are loaded: none are published. See data/banner-evidence/README.md.
set -euo pipefail

PORT="${DB_PORT:-55433}"
DIR="${REGISTRATION_PG_DIR:-$HOME/.registration-local-pg}"
FULL=0
while [ $# -gt 0 ]; do
	case "$1" in
		--port) PORT="$2"; shift 2 ;;
		--dir) DIR="$2"; shift 2 ;;
		--full-validation) FULL=1; shift ;;
		-h|--help) sed -n '2,22p' "$0"; exit 0 ;;
		*) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
	esac
done

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB=registration_local
ADMIN=registration_admin
OWNER=registration_app
READER=registration_reader
case "$DIR" in "$REPO"|"$REPO"/*) echo "refusing to put the database inside the repository ($DIR)" >&2; exit 2 ;; esac

say() { printf '\n== %s\n' "$*"; }
# Run one SQL file; show its PASS lines; stop the script (with the tail of the output) if psql fails.
run_sql() {
	local out
	if out="$(psql -X -v ON_ERROR_STOP=1 -f "$1" 2>&1)"; then
		printf '%s\n' "$out" | grep -E "PASS" || true
	else
		printf '%s\n' "$out" | tail -15 >&2
		echo "FAILED: $1" >&2
		exit 1
	fi
}

# ---- 1. PostgreSQL binaries -------------------------------------------------------------------------------------
find_bin() {
	local c
	for c in "${PG_BIN:-}" "$(pg_config --bindir 2>/dev/null || true)" /opt/homebrew/opt/postgresql@17/bin /opt/homebrew/opt/postgresql@16/bin \
			/opt/homebrew/opt/postgresql@15/bin /usr/local/opt/postgresql@17/bin /usr/local/opt/postgresql@16/bin \
			$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -rV); do
		if [ -n "$c" ] && [ -x "$c/initdb" ] && [ -x "$c/psql" ] && [ -x "$c/pg_ctl" ]; then echo "$c"; return 0; fi
	done
	return 1
}
BIN="$(find_bin)" || { echo "PostgreSQL 15+ not found. Install it (e.g. 'brew install postgresql@17' or 'apt install postgresql') or set PG_BIN." >&2; exit 1; }
MAJOR="$("$BIN/psql" --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
[ "$MAJOR" -ge 15 ] || { echo "PostgreSQL $MAJOR found at $BIN; 15 or newer is required (UNIQUE NULLS NOT DISTINCT)." >&2; exit 1; }
export PATH="$BIN:$PATH"
echo "PostgreSQL $MAJOR ($BIN); cluster directory $DIR; port $PORT"

# ---- 2. private cluster -----------------------------------------------------------------------------------------
random_secret() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24; else od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; fi; }
PGPASS="$DIR/pgpass"
if [ ! -f "$DIR/data/PG_VERSION" ]; then
	say "creating the cluster"
	umask 077
	mkdir -p "$DIR"
	chmod 700 "$DIR"
	A_PW="$(random_secret)"; O_PW="$(random_secret)"; R_PW="$(random_secret)"
	printf '%s' "$A_PW" > "$DIR/.admin-pw"
	initdb -D "$DIR/data" -U "$ADMIN" -E UTF8 --auth-local=scram-sha-256 --auth-host=scram-sha-256 --pwfile="$DIR/.admin-pw" >/dev/null
	rm -f "$DIR/.admin-pw"
	{
		printf '127.0.0.1:%s:*:%s:%s\n' "$PORT" "$ADMIN" "$A_PW"
		printf '127.0.0.1:%s:*:%s:%s\n' "$PORT" "$OWNER" "$O_PW"
		printf '127.0.0.1:%s:*:%s:%s\n' "$PORT" "$READER" "$R_PW"
	} > "$PGPASS"
	chmod 600 "$PGPASS"
	unset A_PW O_PW R_PW
fi
[ -f "$PGPASS" ] || { echo "$PGPASS is missing; delete $DIR and rerun to recreate the cluster." >&2; exit 1; }
export PGPASSFILE="$PGPASS"
if ! pg_ctl -D "$DIR/data" status >/dev/null 2>&1; then
	say "starting the server (127.0.0.1:$PORT only)"
	pg_ctl -D "$DIR/data" -l "$DIR/server.log" -w start -o "-c listen_addresses=127.0.0.1 -c port=$PORT -c unix_socket_directories=" >/dev/null
fi
psql_as() { local user="$1"; shift; psql -X -h 127.0.0.1 -p "$PORT" -U "$user" -v ON_ERROR_STOP=1 "$@"; }
pw_of() { awk -F: -v u="$1" '$4 == u { print $5 }' "$PGPASS"; }

# ---- 3. roles and database (idempotent) ---------------------------------------------------------------------------
say "roles and database"
for role in "$OWNER" "$READER"; do
	if [ "$(psql_as "$ADMIN" -d postgres -Atc "SELECT 1 FROM pg_roles WHERE rolname = '$role'")" != "1" ]; then
		printf "CREATE ROLE %s LOGIN PASSWORD '%s';\n" "$role" "$(pw_of "$role")" | psql_as "$ADMIN" -d postgres -q
	fi
done
if [ "$(psql_as "$ADMIN" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB'")" != "1" ]; then
	psql_as "$ADMIN" -d postgres -q -c "CREATE DATABASE $DB OWNER $OWNER"
fi
export PGHOST=127.0.0.1 PGPORT="$PORT" PGUSER="$OWNER" PGDATABASE="$DB"

# ---- 4. load the layers ---------------------------------------------------------------------------------------------
cd "$REPO"
say "David's CSE 007 snapshot (cse007_snapshot)"
if [ "$(psql -X -Atc "SELECT to_regnamespace('cse007_snapshot') IS NOT NULL")" = "t" ]; then
	echo "already loaded; leaving it untouched"
else
	psql -X -v ON_ERROR_STOP=1 -q -f data/cse007-snapshot/database.sql
fi
run_sql data/cse007-snapshot/verify.sql

say "catalog_api views"
run_sql data/catalog-api/install.sql

say "banner_evidence schema and the seven user-reported links"
run_sql data/banner-evidence/install.sql
run_sql data/banner-evidence/import_user_reported_links.sql

say "read-only role for the backend"
psql -X -v ON_ERROR_STOP=1 -q <<SQL
GRANT CONNECT ON DATABASE $DB TO $READER;
GRANT USAGE ON SCHEMA catalog_api TO $READER;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog_api TO $READER;
SQL
echo "$READER can read catalog_api only"

# ---- 5. verification ------------------------------------------------------------------------------------------------
say "verification"
run_sql data/catalog-api/test_readiness.sql
python3 -I data/catalog-api/check_rules_matter.py | tail -1
run_sql data/banner-evidence/test_banner_evidence.sql
python3 -I data/banner-evidence/collector/test_banner_collect.py 2>&1 | tail -3
if [ "$FULL" = 1 ]; then
	say "full validation (run_validation.py)"
	python3 -I data/banner-evidence/run_validation.py | tail -4
fi

cat <<EOF

== Done. The database is ready.

   Use these in the shell where you run the backend or the tests (the password stays in the pgpass file):
     export PGPASSFILE="$PGPASS"
     export CATALOG_SOURCE=database
     export DB_URL=jdbc:postgresql://127.0.0.1:$PORT/$DB
     export DB_USERNAME=$READER          # read-only; leave DB_PASSWORD unset so the driver reads PGPASSFILE

   Stop the server:   $BIN/pg_ctl -D "$DIR/data" stop
   Start it again:    rerun this script (it reuses the cluster and does not reload data).
EOF
