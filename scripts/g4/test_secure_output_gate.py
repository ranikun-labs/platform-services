import base64
import contextlib
import io
import os
import re
import unittest
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

    def test_failure_output_never_forwarded(self):
        class Child:
            def __init__(self, secret):
                self.stdout = io.BytesIO(secret.encode())
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 0

        captured = []

        def fake_popen(args, env, stdout, stderr):
            captured.append(dict(env))
            return Child(env["CARELOG_E2E_PASSWORD_A"])

        env = dict(CARELOG_E2E_DISPOSABLE="1", CARELOG_E2E_ACCOUNT_A=self.values[0],
                   CARELOG_E2E_PASSWORD_A=self.values[1])
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), \
                patch("subprocess.Popen", side_effect=fake_popen), \
                contextlib.redirect_stdout(output):
            result = main()
        self.assertTrue(result == 1)
        self.assertTrue(len(captured) == 1)
        self.assertTrue(captured[0]["CARELOG_E2E_PASSWORD_A"] not in output.getvalue())
        self.assertTrue(output.getvalue() == (
            "G4_CREDENTIAL_OUTPUT_SCAN: FAIL\nG4_BROWSER_COMMAND: PASS\n"
        ))


if __name__ == "__main__":
    unittest.main()
