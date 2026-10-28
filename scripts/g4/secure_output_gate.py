#!/usr/bin/env python3
"""Run disposable G4 without forwarding raw credential-bearing child output.

Raw stdout/stderr stays in bounded memory; only a fixed verdict is emitted.
This checks the G4/browser command, not every GitHub job log or external system.
"""
import base64
import json
import os
import re
import secrets
import subprocess
import tempfile
from urllib.parse import quote


def variants(values):
    result = set()
    for value in values:
        if not value:
            raise ValueError("missing credential")
        raw = value.encode()
        result.update((raw, quote(value, safe="").encode(),
                       json.dumps(value)[1:-1].encode(),
                       base64.b64encode(raw), base64.urlsafe_b64encode(raw)))
    return tuple(result)


TOKEN_PATTERN = re.compile(
    rb'(?i)(?:bearer\s+[A-Za-z0-9_.~+/-]{12,}|'
    rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|'
    rb'(?:accessToken|refreshToken|password)["\s]*[:=]["\s]*[A-Za-z0-9_+/.-]{12,})'
)

STAGES = frozenset({
    "BUILD", "SERVICES", "IDENTITY_SEED", "CARELOG_SEED", "G4_VERIFY",
    "GATEWAY_PREFLIGHT", "PLAYWRIGHT",
})
ERROR_CATEGORIES = frozenset({
    "CHILD_EXIT", "OUTPUT_SCAN", "EXECUTION", "DIAGNOSTIC",
})


def read_stage(path):
    """Read only an exact stage marker from the runner's private side channel."""
    try:
        with open(path, "rb") as stage_file:
            marker = stage_file.read(64)
    except OSError:
        return None
    if not marker.endswith(b"\n") or marker.count(b"\n") != 1:
        return None
    try:
        stage = marker[:-1].decode("ascii")
    except UnicodeDecodeError:
        return None
    return stage if stage in STAGES else None


def report_failure(stage, category, exit_code):
    # Callers validate the stage/category; only a numeric status can vary.
    if stage not in STAGES or category not in ERROR_CATEGORIES:
        stage, category, exit_code = "BUILD", "DIAGNOSTIC", 2
    print(f"G4_FAILURE stage={stage} category={category} exit_code={int(exit_code)}")


def report_summaries(scanner_leaked, child_code):
    print("G4_CREDENTIAL_OUTPUT_SCAN: " + ("FAIL" if scanner_leaked else "PASS"))
    print("G4_BROWSER_COMMAND: " + ("PASS" if child_code == 0 else "FAIL"))


class Scanner:
    def __init__(self, values):
        self.needles = variants(values)
        self.overlap = max(4096, max(map(len, self.needles)))
        self.tail = b""
        self.leaked = False

    def feed(self, chunk):
        window = self.tail + chunk
        self.leaked |= any(value in window for value in self.needles)
        self.leaked |= TOKEN_PATTERN.search(window) is not None
        self.tail = window[-self.overlap:]


def main():
    env = os.environ.copy()
    if env.get("CARELOG_E2E_DISPOSABLE") != "1":
        print("G4_SECURE_OUTPUT_GATE: BLOCKED_NON_DISPOSABLE_EXECUTION")
        return 2
    if env.get("G4_CARELOG_REAL_API_E2E") != "1":
        print("G4_SECURE_OUTPUT_GATE: BLOCKED_REAL_API_E2E_REQUIRED")
        return 2
    # Replace any inherited values so each disposable run starts with new A/B
    # credentials, independent of the caller's environment.
    env["CARELOG_E2E_ACCOUNT_A"] = (
        f"g4-a-{secrets.token_hex(8)}@example.invalid"
    )
    env["CARELOG_E2E_PASSWORD_A"] = secrets.token_urlsafe(32)
    env["G4_E2E_PASSWORD_B"] = secrets.token_urlsafe(32)
    env["G4_SECURE_OUTPUT_GATE"] = "1"
    scanner = Scanner([
        env["CARELOG_E2E_ACCOUNT_A"],
        env["CARELOG_E2E_PASSWORD_A"],
        env["G4_E2E_PASSWORD_B"],
    ])
    with tempfile.TemporaryDirectory(prefix="g4-stage-") as stage_dir:
        stage_path = os.path.join(stage_dir, "stage")
        with open(stage_path, "wb") as stage_file:
            stage_file.write(b"BUILD\n")
        env["G4_E2E_STAGE_FILE"] = stage_path
        execution_failed = False
        try:
            with subprocess.Popen(["bash", "scripts/g4/run_three_services.sh"], env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as child:
                while chunk := child.stdout.read(8192):
                    scanner.feed(chunk)
                code = child.wait()
        except Exception:
            # Exception text can include command/environment information.
            execution_failed = True
            code = 2

        stage = read_stage(stage_path)
        report_summaries(scanner.leaked, code)
        if stage is None:
            report_failure("BUILD", "DIAGNOSTIC", 2)
            return 2
        if execution_failed:
            report_failure(stage, "EXECUTION", 2)
            return 2
        if scanner.leaked:
            report_failure(stage, "OUTPUT_SCAN", 1)
            return 1
        if code != 0:
            report_failure(stage, "CHILD_EXIT", code)
            return 1
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
