import base64
import contextlib
import io
import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from secure_output_gate import Scanner, main


class OutputGateTests(unittest.TestCase):
    # Synthetic test literals, unrelated to any account or secret.
    values = ["fixture-account@example.invalid", "synthetic-only+password-123456789"]

    def test_clean_output(self):
        scanner = Scanner(self.values)
        scanner.feed(b"35 passed; 2 passed; Docker build finished")
        self.assertFalse(scanner.leaked)

    def test_plain_and_encoded_credentials(self):
        for value in self.values:
            for encoded in (value.encode(), base64.b64encode(value.encode())):
                with self.subTest(kind="plain-or-base64"):
                    scanner = Scanner(self.values)
                    scanner.feed(encoded)
                    self.assertTrue(scanner.leaked)

    def test_chunk_boundary(self):
        scanner = Scanner(self.values)
        value = self.values[1].encode()
        scanner.feed(b"prefix " + value[:9])
        scanner.feed(value[9:] + b" suffix")
        self.assertTrue(scanner.leaked)

    def test_token_and_password_payloads(self):
        for payload in (b'Bearer syntheticTokenValue123456',
                        b'{"accessToken":"syntheticTokenValue123456"}',
                        b'{"refreshToken":"syntheticTokenValue123456"}',
                        b'{"password":"syntheticTokenValue123456"}'):
            scanner = Scanner(self.values)
            scanner.feed(payload)
            self.assertTrue(scanner.leaked)

    def test_bounded_memory(self):
        scanner = Scanner(self.values)
        for _ in range(50):
            scanner.feed(b"x" * 8192)
        self.assertLessEqual(len(scanner.tail), scanner.overlap)

    def test_missing_disposable_flag_fails_closed(self):
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), \
                patch("subprocess.Popen") as popen, \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertTrue(result == 2)
        self.assertTrue(not popen.called)
        self.assertTrue(
            output.getvalue() == "G4_SECURE_OUTPUT_GATE: BLOCKED_NON_DISPOSABLE_EXECUTION\n"
        )

    def test_missing_real_api_flag_fails_closed_before_child(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"CARELOG_E2E_DISPOSABLE": "1"}, clear=True), \
                patch("subprocess.Popen") as popen, \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertEqual(result, 2)
        self.assertFalse(popen.called)
        self.assertEqual(
            output.getvalue(), "G4_SECURE_OUTPUT_GATE: BLOCKED_REAL_API_E2E_REQUIRED\n"
        )

    def test_generates_independent_a_and_b_credentials_per_invocation(self):
        class Child:
            def __enter__(self):
                self.stdout = io.BytesIO(b"clean output")
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 0

        captured = []

        def fake_popen(args, env, stdout, stderr):
            captured.append(dict(env))
            return Child()

        inherited = {
            "CARELOG_E2E_DISPOSABLE": "1",
            "G4_CARELOG_REAL_API_E2E": "1",
            "CARELOG_E2E_ACCOUNT_A": "stale-account@example.invalid",
            "CARELOG_E2E_PASSWORD_A": "synthetic-stale-account-password",
            "G4_E2E_PASSWORD_B": "synthetic-stale-b-password",
        }
        output = io.StringIO()
        with patch.dict(os.environ, inherited, clear=True), \
                patch("subprocess.Popen", side_effect=fake_popen), \
                contextlib.redirect_stdout(output):
            results = (main(), main())

        self.assertTrue(results == (0, 0))
        self.assertTrue(len(captured) == 2)
        account_a = [env["CARELOG_E2E_ACCOUNT_A"] for env in captured]
        password_a = [env["CARELOG_E2E_PASSWORD_A"] for env in captured]
        password_b = [env["G4_E2E_PASSWORD_B"] for env in captured]
        for index, env in enumerate(captured):
            self.assertTrue(
                re.fullmatch(r"g4-a-[0-9a-f]{16}@example\.invalid", account_a[index])
                is not None
            )
            self.assertTrue(account_a[index] != inherited["CARELOG_E2E_ACCOUNT_A"])
            self.assertTrue(password_a[index] != inherited["CARELOG_E2E_PASSWORD_A"])
            self.assertTrue(password_b[index] != inherited["G4_E2E_PASSWORD_B"])
            self.assertTrue(password_a[index] != password_b[index])
            self.assertTrue(env["G4_SECURE_OUTPUT_GATE"] == "1")
        self.assertTrue(account_a[0] != account_a[1])
        self.assertTrue(password_a[0] != password_a[1])
        self.assertTrue(password_b[0] != password_b[1])
        self.assertTrue(
            output.getvalue() == (
                "G4_CREDENTIAL_OUTPUT_SCAN: PASS\nG4_BROWSER_COMMAND: PASS\n"
            ) * 2
        )

    def test_failure_reports_allowlisted_stage_and_hides_child_output(self):
        class Child:
            def __init__(self, stage_path):
                self.stdout = io.BytesIO(b"synthetic HTTP response body with private details")
                with open(stage_path, "wb") as stage_file:
                    stage_file.write(b"G4_VERIFY\n")
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 17

        def fake_popen(args, env, stdout, stderr):
            return Child(env["G4_E2E_STAGE_FILE"])

        env = dict(CARELOG_E2E_DISPOSABLE="1", G4_CARELOG_REAL_API_E2E="1",
                   CARELOG_E2E_ACCOUNT_A=self.values[0],
                   CARELOG_E2E_PASSWORD_A=self.values[1])
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), \
                patch("subprocess.Popen", side_effect=fake_popen), \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertEqual(result, 1)
        self.assertEqual(
            output.getvalue(),
            "G4_CREDENTIAL_OUTPUT_SCAN: PASS\n"
            "G4_BROWSER_COMMAND: FAIL\n"
            "G4_FAILURE stage=G4_VERIFY category=CHILD_EXIT exit_code=17\n",
        )

    def test_gateway_loopback_preflight_reports_distinct_stage(self):
        fake_docker_source = """#!/usr/bin/env python3
import json
import sys

args = sys.argv[1:]
if args[-2:] == ["config", "--quiet"]:
    raise SystemExit(0)
if args[-3:] == ["config", "--format", "json"]:
    print(json.dumps({"services": {"gateway-app": {"ports": []}}}))
    raise SystemExit(0)
if "port" in args and "gateway-app" in args:
    print("0.0.0.0:54321")
    raise SystemExit(0)
if any("SELECT count(*)" in arg for arg in args):
    print("1")
raise SystemExit(0)
"""
        with tempfile.TemporaryDirectory(prefix="g4-fake-docker-") as temporary:
            root = Path(temporary)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            docker = fake_bin / "docker"
            docker.write_text(fake_docker_source, encoding="utf-8")
            docker.chmod(0o755)
            frontend = root / "frontend"
            frontend.mkdir()

            env = {
                "CARELOG_E2E_DISPOSABLE": "1",
                "G4_CARELOG_REAL_API_E2E": "1",
                "CARELOG_E2E_GATEWAY_PORT": "54321",
                "CARELOG_E2E_ACCOUNT_A": self.values[0],
                "CARELOG_E2E_PASSWORD_A": self.values[1],
                "CARELOG_E2E_FE_DIR": str(frontend),
                "PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
            }
            output = io.StringIO()
            with patch.dict(os.environ, env, clear=True):
                with contextlib.redirect_stdout(output):
                    result = main()

        self.assertEqual(result, 1)
        self.assertEqual(
            output.getvalue(),
            "G4_CREDENTIAL_OUTPUT_SCAN: PASS\n"
            "G4_BROWSER_COMMAND: FAIL\n"
            "G4_FAILURE stage=GATEWAY_PREFLIGHT category=CHILD_EXIT exit_code=2\n",
        )

    def test_unknown_or_malformed_diagnostic_fails_closed(self):
        class Child:
            def __init__(self, stage_path, marker):
                self.stdout = io.BytesIO(b"raw child stderr with synthetic details")
                with open(stage_path, "wb") as stage_file:
                    stage_file.write(marker)
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 9

        def fake_popen(args, env, stdout, stderr):
            return Child(env["G4_E2E_STAGE_FILE"], current_marker[0])

        env = dict(CARELOG_E2E_DISPOSABLE="1", G4_CARELOG_REAL_API_E2E="1")
        for marker in (b"UNKNOWN_STAGE\n", b"G4_VERIFY", b"G4_VERIFY\nextra\n", b"\xff\n"):
            current_marker = [marker]
            output = io.StringIO()
            with self.subTest(marker=marker), \
                    patch.dict(os.environ, env, clear=True), \
                    patch("subprocess.Popen", side_effect=fake_popen), \
                    contextlib.redirect_stdout(output):
                result = main()
            self.assertEqual(result, 2)
            self.assertEqual(
                output.getvalue(),
                "G4_CREDENTIAL_OUTPUT_SCAN: PASS\n"
                "G4_BROWSER_COMMAND: FAIL\n"
                "G4_FAILURE stage=BUILD category=DIAGNOSTIC exit_code=2\n",
            )

    def test_missing_diagnostic_fails_closed_with_fixed_summaries(self):
        class Child:
            def __init__(self, stage_path):
                self.stdout = io.BytesIO(b"unforwarded child output")
                os.remove(stage_path)
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 17

        def fake_popen(args, env, stdout, stderr):
            return Child(env["G4_E2E_STAGE_FILE"])

        env = dict(CARELOG_E2E_DISPOSABLE="1", G4_CARELOG_REAL_API_E2E="1")
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), \
                patch("subprocess.Popen", side_effect=fake_popen), \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertEqual(result, 2)
        self.assertEqual(
            output.getvalue(),
            "G4_CREDENTIAL_OUTPUT_SCAN: PASS\n"
            "G4_BROWSER_COMMAND: FAIL\n"
            "G4_FAILURE stage=BUILD category=DIAGNOSTIC exit_code=2\n",
        )

    def test_popen_exception_has_fixed_summaries_and_no_exception_text(self):
        env = dict(CARELOG_E2E_DISPOSABLE="1", G4_CARELOG_REAL_API_E2E="1")
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), \
                patch("subprocess.Popen", side_effect=RuntimeError("synthetic private details")), \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertEqual(result, 2)
        self.assertNotIn("synthetic private details", output.getvalue())
        self.assertEqual(
            output.getvalue(),
            "G4_CREDENTIAL_OUTPUT_SCAN: PASS\n"
            "G4_BROWSER_COMMAND: FAIL\n"
            "G4_FAILURE stage=BUILD category=EXECUTION exit_code=2\n",
        )

    def test_output_scan_failure_never_forwards_credentials(self):
        class Child:
            def __init__(self, secret):
                self.stdout = io.BytesIO(secret.encode())
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 0

        def fake_popen(args, env, stdout, stderr):
            return Child(env["CARELOG_E2E_PASSWORD_A"])

        env = dict(CARELOG_E2E_DISPOSABLE="1", G4_CARELOG_REAL_API_E2E="1",
                   CARELOG_E2E_ACCOUNT_A=self.values[0],
                   CARELOG_E2E_PASSWORD_A=self.values[1])
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), \
                patch("subprocess.Popen", side_effect=fake_popen), \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertEqual(result, 1)
        self.assertNotIn(self.values[1], output.getvalue())
        self.assertEqual(
            output.getvalue(),
            "G4_CREDENTIAL_OUTPUT_SCAN: FAIL\n"
            "G4_BROWSER_COMMAND: PASS\n"
            "G4_FAILURE stage=BUILD category=OUTPUT_SCAN exit_code=1\n",
        )


if __name__ == "__main__":
    unittest.main()
