"""Tests of the shared-database guard without a database (synthetic payload only): python3 -I data/csb-research/test_shared_db_guard.py
The same guard against a real connection (service file, libpq defaults, the server's own port) is exercised by run_csb_validation.py."""
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import import_csb  # noqa: E402
import shared_db_guard as guard  # noqa: E402
import synthetic_fixture as fx  # noqa: E402

LIBPQ_VARS = ("PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGSERVICE", "PGSERVICEFILE")


class EnvironmentPreCheck(unittest.TestCase):
    def test_the_unified_team_server_and_the_earlier_one_are_both_shared(self):
        self.assertIn("55437", guard.SHARED_PORTS)              # the regression: only 55433 was listed
        self.assertIn("55433", guard.SHARED_PORTS)
        self.assertIn("registration_unified_spring2027", guard.SHARED_DATABASES)
        self.assertIn("registration_cse007_local", guard.SHARED_DATABASES)

    def test_an_environment_naming_the_shared_server_is_refused_without_approval(self):
        for environ in ({"PGPORT": "55437"}, {"PGPORT": "55433"}, {"PGDATABASE": "registration_unified_spring2027"}, {"PGHOST": "127.0.0.1", "PGPORT": "55437", "PGDATABASE": "x"}):
            self.assertIn("names the shared database server", guard.env_refusal(False, environ), environ)
            self.assertIsNone(guard.env_refusal(True, environ), environ)

    def test_a_remote_host_is_refused_even_with_approval(self):
        for environ in ({"PGHOST": "100.64.0.7"}, {"PGHOST": "db.example.org"}, {"PGHOSTADDR": "10.0.0.5"}, {"PGHOST": "127.0.0.1,10.0.0.5"}):
            self.assertIn("not a loopback address", guard.env_refusal(True, environ), environ)
        self.assertIn("several hosts or ports", guard.env_refusal(True, {"PGHOST": "127.0.0.1,localhost"}))

    def test_local_connections_pass_the_pre_check(self):
        for environ in ({}, {"PGHOST": "127.0.0.1", "PGPORT": "55442"}, {"PGHOST": "localhost"}, {"PGHOST": "::1"}, {"PGHOST": "/tmp/pg-socket"}, {"PGHOSTADDR": "127.0.0.1"}):
            self.assertIsNone(guard.env_refusal(False, environ), environ)

    def test_the_environment_alone_cannot_see_a_service_file_so_the_server_must_be_asked(self):
        self.assertIsNone(guard.env_refusal(False, {"PGSERVICE": "team"}))
        sql = guard.server_guard_sql(False)
        for value in guard.SHARED_DATABASES + guard.SHARED_PORTS:
            self.assertIn(f"'{value}'", sql)
        for probe in ("current_database()", "current_setting('port')", "pg_database", "inet_server_addr()"):
            self.assertIn(probe, sql)

    def test_approval_waives_only_the_shared_database_test(self):
        self.assertIn("IF true AND reason IS NOT NULL", guard.server_guard_sql(False))
        self.assertIn("IF false AND reason IS NOT NULL", guard.server_guard_sql(True))
        self.assertIn("not on a loopback address", guard.server_guard_sql(True))


class Importer(unittest.TestCase):
    def setUp(self):
        self.saved = {k: os.environ.pop(k) for k in LIBPQ_VARS if k in os.environ}
        self.tmp = tempfile.TemporaryDirectory()
        self.payload = Path(self.tmp.name) / "payload.json"
        self.payload.write_text(json.dumps(fx.make_payload(json.loads((HERE / "csb_program_2026_27.json").read_text(encoding="utf-8")))), encoding="utf-8")

    def tearDown(self):
        for k in LIBPQ_VARS:
            os.environ.pop(k, None)
        os.environ.update(self.saved)
        self.tmp.cleanup()

    def test_the_shared_port_in_the_environment_is_refused_before_any_connection(self):
        for var, value in (("PGPORT", "55437"), ("PGDATABASE", "registration_unified_spring2027")):
            os.environ[var] = value
            with mock.patch.object(import_csb.subprocess, "run", side_effect=AssertionError("psql must not run")) as run:
                with self.assertRaises(SystemExit) as exit_:
                    import_csb.main(["--payload", str(self.payload)])
            self.assertIn("names the shared database server", str(exit_.exception.code))
            run.assert_not_called()
            del os.environ[var]

    def test_the_server_guard_runs_inside_the_import_transaction_before_any_write(self):
        for flags in ([], ["--dry-run"], ["--allow-shared"]):
            done = subprocess.CompletedProcess([], 0, stdout="run=1\ncounts|{}\nfingerprint|x\n", stderr="")
            with mock.patch.object(import_csb.subprocess, "run", return_value=done) as run, contextlib.redirect_stdout(io.StringIO()):
                import_csb.main(["--payload", str(self.payload)] + flags)
            sql = run.call_args.kwargs["input"]
            begin, guard_at, write = sql.index("BEGIN;"), sql.index("DO $csb_guard$"), sql.index("catalog_csb.import_csb(")
            self.assertLess(begin, guard_at, flags)
            self.assertLess(guard_at, write, flags)
            self.assertIn(guard.server_guard_sql("--allow-shared" in flags), sql)


if __name__ == "__main__":
    unittest.main()
