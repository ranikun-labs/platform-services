import base64
import contextlib
import io
import os
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

    def test_missing_credentials_fail_closed(self):
        with patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(), 2)

    def test_failure_output_never_forwarded(self):
        class Child:
            def __enter__(self):
                self.stdout = io.BytesIO(OutputGateTests.values[1].encode())
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                return 0
        env = dict(CARELOG_E2E_DISPOSABLE="1", CARELOG_E2E_ACCOUNT_A=self.values[0],
                   CARELOG_E2E_PASSWORD_A=self.values[1])
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), patch("subprocess.Popen", return_value=Child()), contextlib.redirect_stdout(output):
            self.assertEqual(main(), 1)
        self.assertNotIn(self.values[1], output.getvalue())
        self.assertIn("G4_CREDENTIAL_OUTPUT_SCAN: FAIL", output.getvalue())


if __name__ == "__main__":
    unittest.main()
