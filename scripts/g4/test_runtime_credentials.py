#!/usr/bin/env python3
"""Static, non-Docker checks for runtime G4 test credentials."""

import ast
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def service_block(source: str, service: str) -> str:
    lines = source.splitlines()
    start = next(i for i, line in enumerate(lines) if line == f"  {service}:")
    end = next(
        (i for i in range(start + 1, len(lines))
         if lines[i].startswith("  ") and not lines[i].startswith("    ")
         and lines[i].strip().endswith(":")),
        len(lines),
    )
    return "\n".join(lines[start:end])


def insert_values(source: str, table: str, occurrence: int = 0) -> list[str]:
    pattern = re.compile(
        rf"INSERT\s+INTO\s+{re.escape(table)}\s*\([^;]*?\)\s*VALUES\s*\(",
        re.I | re.S,
    )
    matches = list(pattern.finditer(source))
    if occurrence >= len(matches):
        return []
    start = matches[occurrence].end()
    depth = 1
    quoted = False
    parts = []
    part_start = start
    i = start
    while i < len(source):
        char = source[i]
        if quoted:
            if char == "'":
                if i + 1 < len(source) and source[i + 1] == "'":
                    i += 2
                    continue
                quoted = False
        elif char == "'":
            quoted = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                parts.append(source[part_start:i].strip())
                return parts
        elif char == "," and depth == 1:
            parts.append(source[part_start:i].strip())
            part_start = i + 1
        i += 1
    return []


class RuntimeCredentialWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = (ROOT / "scripts/g4/run_three_services.sh").read_text()
        cls.identity_script = (ROOT / "scripts/test-gateway-identity-e2e.sh").read_text()
        cls.verifier = (ROOT / "scripts/g4/verify_three_services.py").read_text()
        cls.identity_seed = (ROOT / "scripts/e2e/seed.sql").read_text()
        cls.identity_entrypoint = (ROOT / "scripts/e2e/gateway_identity_e2e.py").read_text()
        cls.carelog_seed = (ROOT / "scripts/g4/seed-carelog.sql").read_text()
        cls.identity_compose = (ROOT / "docker-compose.identity-e2e.yml").read_text()
        cls.g4_compose = (ROOT / "docker-compose.g4-e2e.yml").read_text()
        cls.real_api_workflow = (ROOT / ".github/workflows/carelog-real-api-e2e.yml").read_text()

    @staticmethod
    def _write_fake_docker(directory: Path) -> None:
        docker_stub = directory / "docker"
        docker_stub.write_text(
            r"""#!/usr/bin/env python3
import os
import sys

if os.environ.get("FAKE_DOCKER_CAPTURE_CREDENTIALS") == "1" and sys.argv[-2:] == ["config", "--quiet"]:
    keys = ("CARELOG_E2E_ACCOUNT_A", "CARELOG_E2E_PASSWORD_A", "G4_E2E_PASSWORD_B")
    report_fd = int(os.environ["FAKE_DOCKER_REPORT_FD"])
    os.write(report_fd, "\0".join(os.environ.get(key, "") for key in keys).encode())
    raise SystemExit(79)

if os.environ.get("FAKE_DOCKER_REPORT_CALLS") == "1":
    report_fd = int(os.environ["FAKE_DOCKER_REPORT_FD"])
    os.write(report_fd, b"docker-called")
    raise SystemExit(88)

raise SystemExit(0)
"""
        )
        docker_stub.chmod(0o755)

    @staticmethod
    def _invoke_with_fake_docker(
        directory: Path, environment: dict[str, str]
    ) -> tuple[subprocess.CompletedProcess[bytes], bytes]:
        read_fd, write_fd = os.pipe()
        child_environment = dict(environment)
        child_environment["PATH"] = (
            f"{directory}{os.pathsep}{child_environment.get('PATH', os.defpath)}"
        )
        child_environment["FAKE_DOCKER_REPORT_FD"] = str(write_fd)
        try:
            # The stub reports generated values through this anonymous pipe, never stdout, stderr, or a file.
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/g4/run_three_services.sh")],
                env=child_environment,
                pass_fds=(write_fd,),
                capture_output=True,
                timeout=10,
            )
        finally:
            os.close(write_fd)

        report = bytearray()
        try:
            while chunk := os.read(read_fd, 4096):
                report.extend(chunk)
        finally:
            os.close(read_fd)
        return result, bytes(report)

    @staticmethod
    def _real_api_test_environment() -> dict[str, str]:
        return {
            "PATH": os.environ.get("PATH", os.defpath),
            "G4_CARELOG_REAL_API_E2E": "1",
            "CARELOG_E2E_DISPOSABLE": "1",
            "CARELOG_E2E_GATEWAY_PORT": "18080",
            "CARELOG_E2E_FE_DIR": str(ROOT),
            "CARELOG_E2E_ACCOUNT_A": "inherited-test-account@example.invalid",
            "CARELOG_E2E_PASSWORD_A": "inherited-test-password",
            "G4_E2E_PASSWORD_B": "inherited-test-password-b",
            "FAKE_DOCKER_CAPTURE_CREDENTIALS": "1",
        }

    def test_verifier_reads_a_login_from_environment_and_keeps_static_ids(self):
        tree = ast.parse(self.verifier)
        assignments = {}
        for node in ast.walk(tree):
            if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.Subscript):
                continue
            if len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
                continue
            key = node.value.slice
            if isinstance(key, ast.Constant):
                assignments[node.targets[0].id] = key.value
        self.assertEqual(assignments.get("LOGIN_ACCOUNT_A"), "CARELOG_E2E_ACCOUNT_A")
        self.assertEqual(assignments.get("LOGIN_PASSWORD_A"), "CARELOG_E2E_PASSWORD_A")
        self.assertEqual(assignments.get("LOGIN_PASSWORD_B"), "G4_E2E_PASSWORD_B")

        static_ids = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign) and len(node.targets) == 1 \
                    and isinstance(node.targets[0], ast.Name) \
                    and node.targets[0].id in ("ACCOUNT_A", "ACCOUNT_B") \
                    and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                static_ids.add(node.targets[0].id)
        self.assertEqual(static_ids, {"ACCOUNT_A", "ACCOUNT_B"})

        login_payloads = []
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                    and node.func.id == "req"):
                continue
            payload = next((arg for arg in node.args if isinstance(arg, ast.Dict)), None)
            if payload is None or len(payload.keys) != 2:
                continue
            keys = [key.value for key in payload.keys if isinstance(key, ast.Constant)]
            if set(keys) == {"userId", "password"}:
                login_payloads.append((node.lineno,
                                       {key.value: value for key, value in zip(payload.keys, payload.values)}))
        login_payloads.sort(key=lambda item: item[0])
        self.assertEqual(len(login_payloads), 2)
        login_a, login_b = login_payloads[0][1], login_payloads[1][1]
        self.assertTrue(isinstance(login_a["userId"], ast.Name)
                        and login_a["userId"].id == "LOGIN_ACCOUNT_A",
                        "A login id must come from the environment")
        self.assertTrue(isinstance(login_a["password"], ast.Name)
                        and login_a["password"].id == "LOGIN_PASSWORD_A",
                        "A password must come from the environment")
        self.assertTrue(isinstance(login_b["userId"], ast.Constant),
                        "B fixture login ID should remain static")
        self.assertTrue(isinstance(login_b["password"], ast.Name)
                        and login_b["password"].id == "LOGIN_PASSWORD_B",
                        "B password must be generated for this run")

    def test_seed_sql_uses_psql_environment_and_bcrypt_without_changing_fixture_ids(self):
        account_getenv = r"\getenv carelog_e2e_account_a CARELOG_E2E_ACCOUNT_A"
        password_getenv = r"\getenv carelog_e2e_password_a CARELOG_E2E_PASSWORD_A"
        password_b_getenv = r"\getenv g4_e2e_password_b G4_E2E_PASSWORD_B"
        hash_expr_a = "crypt(:'carelog_e2e_password_a', gen_salt('bf', 10))"
        hash_expr_b = "crypt(:'g4_e2e_password_b', gen_salt('bf', 10))"
        for source in (self.identity_seed, self.carelog_seed):
            self.assertTrue(account_getenv in source, "account environment read is missing")
            self.assertTrue(password_getenv in source, "password environment read is missing")
            self.assertTrue("CREATE EXTENSION IF NOT EXISTS pgcrypto;" in source,
                            "pgcrypto extension declaration is missing")
            self.assertTrue(password_b_getenv in source, "B password environment read is missing")
            self.assertTrue(hash_expr_a in source and hash_expr_b in source,
                            "runtime bcrypt expressions are missing")
            self.assertTrue("-v carelog_e2e_password_a=" not in source,
                            "password must not be passed as a psql command-line argument")
        self.assertGreaterEqual(self.identity_seed.count(":'carelog_e2e_account_a'"), 2)
        self.assertGreaterEqual(self.carelog_seed.count(":'carelog_e2e_account_a'"), 2)

        # A's fixture IDs remain literal SQL values; only login/email and password vary.
        identity_account = insert_values(self.identity_seed, "identity.platform_accounts")
        identity_login = insert_values(self.identity_seed, "identity.password_credentials")
        identity_account_b = insert_values(self.identity_seed, "identity.platform_accounts", 1)
        identity_login_b = insert_values(self.identity_seed, "identity.password_credentials", 1)
        carelog_account = insert_values(self.carelog_seed, "platform_accounts")
        carelog_user = insert_values(self.carelog_seed, "users")
        carelog_account_b = insert_values(self.carelog_seed, "platform_accounts", 1)
        carelog_user_b = insert_values(self.carelog_seed, "users", 1)
        self.assertTrue(len(identity_account) == 5 and identity_account[0].startswith("'"),
                        "Identity account ID must remain a SQL literal")
        self.assertTrue(identity_account[2] == ":'carelog_e2e_account_a'",
                        "Identity primary email must use runtime login")
        self.assertTrue(len(identity_login) == 5 and identity_login[0].startswith("'"),
                        "Identity credential account ID must remain a SQL literal")
        self.assertTrue(identity_login[1] == ":'carelog_e2e_account_a'",
                        "Identity login ID must use runtime login")
        self.assertTrue(identity_login[2].startswith("crypt(:'carelog_e2e_password_a'"),
                        "Identity password hash must use runtime password")
        self.assertTrue(len(identity_account_b) == 5 and identity_account_b[0].startswith("'"),
                        "Identity B account ID must remain a SQL literal")
        self.assertTrue(len(identity_login_b) == 5 and identity_login_b[0].startswith("'")
                        and identity_login_b[1].startswith("'"),
                        "Identity B account and login IDs must remain SQL literals")
        self.assertTrue(identity_login_b[2].startswith("crypt(:'g4_e2e_password_b'"),
                        "Identity B password must use the per-run value")
        self.assertTrue(len(carelog_account) == 5 and carelog_account[0].startswith("'"),
                        "Carelog account ID must remain a SQL literal")
        self.assertTrue(carelog_account[2] == ":'carelog_e2e_account_a'",
                        "Carelog primary email must use runtime login")
        self.assertTrue(len(carelog_user) == 11
                        and all(carelog_user[i].startswith("'") for i in (2, 3, 4, 10)),
                        "Carelog organization, public, user, and account IDs must remain literals")
        self.assertTrue(carelog_user[5] == ":'carelog_e2e_account_a'",
                        "Carelog user email must use runtime login")
        self.assertTrue(carelog_user[6].startswith("crypt(:'carelog_e2e_password_a'"),
                        "Carelog password hash must use runtime password")
        self.assertTrue(len(carelog_account_b) == 5 and carelog_account_b[0].startswith("'"),
                        "Carelog B account ID must remain a SQL literal")
        self.assertTrue(len(carelog_user_b) == 11
                        and all(carelog_user_b[i].startswith("'") for i in (2, 3, 4, 5, 9, 10)),
                        "Carelog B identity, email, and account IDs must remain literals")
        self.assertTrue(carelog_user_b[6].startswith("crypt(:'g4_e2e_password_b'"),
                        "Carelog B password must use the per-run value")

    def test_compose_forwards_credentials_only_to_seed_and_verifier_services(self):
        identity_seed = service_block(self.identity_compose, "identity-e2e-seed")
        identity_runner = service_block(self.identity_compose, "identity-e2e-runner")
        carelog_db = service_block(self.g4_compose, "carelog-postgres")
        verifier = service_block(self.g4_compose, "g4-three-service-runner")
        for block in (identity_seed, carelog_db, verifier):
            self.assertTrue("CARELOG_E2E_ACCOUNT_A:" in block,
                            "account environment forwarding is missing")
            self.assertTrue("CARELOG_E2E_PASSWORD_A:" in block,
                            "password environment forwarding is missing")
            self.assertTrue("G4_E2E_PASSWORD_B:" in block,
                            "B password environment forwarding is missing")
        self.assertTrue("PLATFORM_E2E_LOGIN_ID: ${CARELOG_E2E_ACCOUNT_A:?required}" in identity_runner,
                        "identity runner must use the generated A login")
        self.assertTrue("PLATFORM_E2E_PASSWORD: ${CARELOG_E2E_PASSWORD_A:?required}" in identity_runner,
                        "identity runner must use the generated A password")

    def test_browser_mode_validates_disposable_config_before_generating_credentials(self):
        required_loop = self.runner.index(
            "for required in CARELOG_E2E_DISPOSABLE CARELOG_E2E_GATEWAY_PORT "
            "CARELOG_E2E_FE_DIR"
        )
        generation = self.runner.index(
            "# Disposable browser runs always use fresh synthetic identities"
        )
        self.assertLess(required_loop, generation)
        self.assertTrue("exit 2" in self.runner[:generation],
                        "invalid browser configuration must fail closed before credential generation")
        self.assertIn("if [[ \"$REAL_API_E2E\" == \"1\" ]]; then",
                      self.runner[generation:])
        self.assertTrue("CARELOG_E2E_ACCOUNT_A=\"g4-a-$(python3 -c 'import secrets;" in self.runner,
                        "per-run A account generation is missing")
        self.assertTrue("CARELOG_E2E_PASSWORD_A=\"$(python3 -c 'import secrets;" in self.runner,
                        "per-run A password generation is missing")
        self.assertTrue("export CARELOG_E2E_ACCOUNT_A CARELOG_E2E_PASSWORD_A" in self.runner,
                        "runtime credential export is missing")
        self.assertTrue("G4_E2E_PASSWORD_B=\"$(python3 -c 'import secrets;" in self.runner,
                        "G4 verifier B password generation is missing")
        self.assertEqual(self.runner.count("::add-mask::%s"), 3,
                         "Actions must mask the account and both passwords")
        for name in ("CARELOG_E2E_ACCOUNT_A", "CARELOG_E2E_PASSWORD_A", "G4_E2E_PASSWORD_B"):
            mask_statement = f"printf '::add-mask::%s\\n' \"${name}\""
            self.assertIn(mask_statement, self.runner,
                          "each runtime credential must be masked")
            self.assertLess(self.runner.index(mask_statement),
                            self.runner.index('"${COMPOSE[@]}" config --quiet'),
                            "every runtime credential must be masked before child startup")
        actions_guard = self.runner.index('if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then')
        generated = self.runner.index("# Disposable browser runs always use fresh synthetic identities")
        masked = self.runner.index("::add-mask::%s")
        compose = self.runner.index("COMPOSE=(")
        first_child = self.runner.index('"${COMPOSE[@]}" config --quiet')
        self.assertLess(generated, masked)
        self.assertLess(actions_guard, masked)
        self.assertLess(masked, compose, "credentials must be masked before Compose starts")
        self.assertLess(masked, first_child, "credentials must be masked before child startup")
        self.assertTrue(not re.search(r"(?m)^\s*set\s+-[^\n]*x", self.runner),
                        "shell xtrace is enabled")
        for line in self.runner.splitlines():
            if "::add-mask::%s" in line:
                continue
            self.assertFalse(
                re.search(
                    r"(?i)^\s*(?:echo|printf)\b.*\$\{?(?:CARELOG_E2E_ACCOUNT_A|"
                    r"CARELOG_E2E_PASSWORD_A|G4_E2E_PASSWORD_B)\}?",
                    line,
                ),
                "credential values must not be sent to shell output",
            )
        self.assertIn('trap cleanup EXIT INT TERM', self.runner)
        self.assertIn('"${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true',
                      self.runner)
        self.assertIn('if [[ "$REAL_API_E2E" != "1" ]]; then', self.runner)
        self.assertIn(
            r"sed -E 's/(password|secret|token)=([^[:space:]]+)/\1=[REDACTED]/Ig' || true",
            self.runner,
        )
        self.assertTrue("G4_E2E_PASSWORD_B=\"$(python3 -c 'import secrets;" in self.identity_script,
                        "identity-only test must generate a B password")
        self.assertTrue("::add-mask::%s\\n" in self.identity_script,
                        "identity-only Actions run must mask runtime credentials")
        self.assertTrue(not re.search(r"(?m)^\s*set\s+-[^\n]*x", self.identity_script),
                        "identity-only test must not enable shell xtrace")

    def test_real_api_generates_fresh_values_across_two_invocations_without_logging(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fake_docker_directory = Path(temporary_directory)
            self._write_fake_docker(fake_docker_directory)
            generated_values = []

            for _ in range(2):
                result, report = self._invoke_with_fake_docker(
                    fake_docker_directory,
                    self._real_api_test_environment(),
                )
                self.assertEqual(result.returncode, 79,
                                 "fake Docker should stop before any service starts")
                parts = report.split(b"\0")
                self.assertEqual(len(parts), 3,
                                 "fake Docker should receive A login, A password, and B password")
                try:
                    account_a, password_a, password_b = (part.decode("ascii") for part in parts)
                except UnicodeDecodeError:
                    self.fail("generated credential format must be ASCII")

                self.assertTrue(
                    re.fullmatch(r"g4-a-[0-9a-f]{16}@example\.invalid", account_a) is not None,
                    "REAL_API_E2E must generate a fresh synthetic Account A login",
                )
                self.assertTrue(
                    re.fullmatch(r"[A-Za-z0-9_-]{32,}", password_a) is not None,
                    "REAL_API_E2E must generate a fresh Account A password",
                )
                self.assertTrue(
                    re.fullmatch(r"[A-Za-z0-9_-]{32,}", password_b) is not None,
                    "REAL_API_E2E must generate a fresh Account B password",
                )
                credential_bytes = tuple(value.encode("ascii") for value in
                                         (account_a, password_a, password_b))
                self.assertFalse(
                    any(value in result.stdout or value in result.stderr for value in credential_bytes),
                    "generated credentials appeared in script stdout or stderr",
                )
                generated_values.append((account_a, password_a, password_b))

            if not all(left != right for left, right in zip(*generated_values)):
                self.fail("every synthetic credential must differ across invocations")

    def test_missing_or_malformed_disposable_flag_fails_before_child_startup(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fake_docker_directory = Path(temporary_directory)
            self._write_fake_docker(fake_docker_directory)

            for disposable_value in (None, "not-1"):
                environment = {
                    "PATH": os.environ.get("PATH", os.defpath),
                    "G4_CARELOG_REAL_API_E2E": "1",
                    "CARELOG_E2E_GATEWAY_PORT": "18080",
                    "CARELOG_E2E_FE_DIR": str(ROOT),
                    "FAKE_DOCKER_REPORT_CALLS": "1",
                }
                if disposable_value is not None:
                    environment["CARELOG_E2E_DISPOSABLE"] = disposable_value

                result, child_calls = self._invoke_with_fake_docker(
                    fake_docker_directory,
                    environment,
                )
                self.assertEqual(result.returncode, 2,
                                 "missing or malformed disposable mode must fail closed")
                self.assertEqual(child_calls, b"",
                                 "invalid disposable mode must fail before any child starts")

    def test_real_api_workflow_has_no_persistent_credential_secret_inputs(self):
        self.assertNotIn("${{ secrets.CARELOG_E2E_ACCOUNT_A }}", self.real_api_workflow)
        self.assertNotIn("${{ secrets.CARELOG_E2E_PASSWORD_A }}", self.real_api_workflow)
        self.assertNotIn("Required Carelog E2E repository secrets are not configured.", self.real_api_workflow)
        self.assertNotRegex(self.real_api_workflow, r"\$\{\{\s*secrets\.")
        self.assertIn('G4_CARELOG_REAL_API_E2E: "1"', self.real_api_workflow)
        self.assertIn('CARELOG_E2E_DISPOSABLE: "1"', self.real_api_workflow)
        self.assertIn("if: always()", self.real_api_workflow)
        self.assertIn("down --volumes --remove-orphans", self.real_api_workflow)

    def test_identity_runner_requires_login_and_password_environment(self):
        tree = ast.parse(self.identity_entrypoint)
        assignments = {}
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign) and len(node.targets) == 1 \
                    and isinstance(node.targets[0], ast.Name) \
                    and node.targets[0].id in ("LOGIN_ID", "PASSWORD"):
                assignments[node.targets[0].id] = node.value

        def is_empty_env_lookup(node, key):
            return (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "get"
                and isinstance(node.func.value, ast.Attribute)
                and node.func.value.attr == "environ"
                and isinstance(node.func.value.value, ast.Name)
                and node.func.value.value.id == "os"
                and len(node.args) == 2
                and isinstance(node.args[0], ast.Constant)
                and node.args[0].value == key
                and isinstance(node.args[1], ast.Constant)
                and node.args[1].value == ""
            )

        self.assertTrue(is_empty_env_lookup(assignments.get("LOGIN_ID"), "PLATFORM_E2E_LOGIN_ID"),
                        "identity runner must not default the account login")
        self.assertTrue(is_empty_env_lookup(assignments.get("PASSWORD"), "PLATFORM_E2E_PASSWORD"),
                        "identity runner must not default the password")
        self.assertTrue("if not all((JWT_SECRET, LOGIN_ID, PASSWORD, FAKE_CODE))" in self.identity_entrypoint,
                        "identity runner must fail closed when credentials are missing")

        password_payloads = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.Dict):
                continue
            keys = [key.value if isinstance(key, ast.Constant) else None for key in node.keys]
            if "password" in keys:
                password_payloads.append(node.values[keys.index("password")])

        def is_env_or_random_password(node):
            if isinstance(node, ast.Name):
                return node.id == "PASSWORD"
            return (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "token_urlsafe"
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "secrets"
            )

        self.assertTrue(len(password_payloads) >= 3
                        and all(is_env_or_random_password(node) for node in password_payloads),
                        "identity runner must not embed password literals in request bodies")
        self.assertTrue(any(isinstance(node, ast.Call)
                            and isinstance(node.func, ast.Attribute)
                            and node.func.attr == "token_urlsafe"
                            for node in password_payloads),
                        "negative login check must use a generated wrong password")

        env = os.environ.copy()
        env.pop("PLATFORM_E2E_LOGIN_ID", None)
        env.pop("PLATFORM_E2E_PASSWORD", None)
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts/e2e/gateway_identity_e2e.py"), "--phase", "functional"],
            env=env, capture_output=True, text=True, timeout=10,
        )
        self.assertTrue(result.returncode == 2,
                        "identity runner should exit before network work when credentials are missing")


if __name__ == "__main__":
    unittest.main()
