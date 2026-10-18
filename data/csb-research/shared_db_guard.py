"""Recognises the team's shared PostgreSQL server from the ACTUAL connection, not only from environment variables (stdlib only).

libpq does not need PGHOST, PGPORT or PGDATABASE: it also takes them from a service file (PGSERVICE, PGSERVICEFILE, ~/.pg_service.conf),
from PGHOSTADDR, or from its defaults (the local socket, port 5432, a database named after the user). A check of the environment alone misses
every connection that reaches the shared server that way, so there are two layers:
  env_refusal()       a quick look at the environment before anything connects;
  server_guard_sql()  a DO block that asks the server itself: current_database(), the server's own port setting (also behind a socket,
                      a tunnel or a forwarded port), whether this cluster holds a shared database, and the server address of the connection.
                      It runs INSIDE the session and transaction that writes, so the check and the write cannot reach different servers.
--allow-shared (the team's deliberate approval) waives the shared-database test; a non-loopback server is refused even then: the approved
install runs on the database host itself (MERGE_INTO_UNIFIED_DATABASE.md).
"""
import os

# 55437: the unified team server that holds registration_unified_spring2027 (data/professor-ratings/README.md); 55433: the earlier server of
# registration_cse007_local (data/setup-local-database.sh). A scratch database on either server counts as shared: use a separate cluster.
SHARED_DATABASES = ("registration_cse007_local", "registration_unified_spring2027")
SHARED_PORTS = ("55433", "55437")
LOCAL_HOSTS = ("", "127.0.0.1", "localhost", "::1")
APPROVAL_HINT = "use an isolated copy on a separate cluster, or pass --allow-shared once the team has approved the install"


def env_refusal(allow_shared, environ=None, hint=APPROVAL_HINT):
    """The environment-only pre-check: a reason to refuse, or None (no objection; the server check still decides)."""
    environ = os.environ if environ is None else environ
    for var in ("PGHOST", "PGHOSTADDR"):
        for host in environ.get(var, "").split(","):
            host = host.strip()
            if host and not host.startswith("/") and host not in LOCAL_HOSTS:   # a leading "/" is a local socket directory
                return f"refusing: {var} is not a loopback address"
    if "," in environ.get("PGHOST", "") or "," in environ.get("PGPORT", ""):
        return "refusing: several hosts or ports in PGHOST/PGPORT (one connection must reach one known server)"
    if not allow_shared and (environ.get("PGDATABASE", "") in SHARED_DATABASES or environ.get("PGPORT", "") in SHARED_PORTS):
        return "refusing: the environment names the shared database server; " + hint
    return None


def loopback_sql(address):
    """SQL predicate: the inet value is a loopback address (IPv4, IPv6, or IPv4-mapped IPv6)."""
    return f"({address} <<= inet '127.0.0.0/8' OR {address} = inet '::1' OR {address} <<= inet '::ffff:127.0.0.0/104')"


def server_guard_sql(allow_shared, hint=APPROVAL_HINT):
    """A DO block that raises 'refusing: ...' when the CONNECTED server is a shared one (unless allowed) or is not on loopback."""
    names = "ARRAY[" + ", ".join(f"'{n}'" for n in SHARED_DATABASES) + "]::text[]"
    ports = "ARRAY[" + ", ".join(f"'{p}'" for p in SHARED_PORTS) + "]::text[]"
    return f"""DO $csb_guard$
DECLARE
	reason text;
BEGIN
	IF inet_server_addr() IS NOT NULL AND NOT {loopback_sql('inet_server_addr()')} THEN
		RAISE EXCEPTION 'refusing: the connection reached a server that is not on a loopback address';
	END IF;
	reason := CASE
		WHEN current_database() = ANY ({names}) THEN 'the database is ' || current_database()
		WHEN current_setting('port') = ANY ({ports}) THEN 'the server port is ' || current_setting('port')
		ELSE (SELECT 'this server also holds ' || string_agg(datname, ', ' ORDER BY datname) FROM pg_database WHERE datname = ANY ({names}))
	END;
	IF {'false' if allow_shared else 'true'} AND reason IS NOT NULL THEN
		RAISE EXCEPTION 'refusing: this is the shared database server (%); {hint}', reason;
	END IF;
END $csb_guard$;"""
