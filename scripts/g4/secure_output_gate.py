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
    try:
        with subprocess.Popen(["bash", "scripts/g4/run_three_services.sh"], env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as child:
            while chunk := child.stdout.read(8192):
                scanner.feed(chunk)
            code = child.wait()
    except Exception:
        # Exception text can include command/environment information.
        print("G4_SECURE_OUTPUT_GATE: BLOCKED_EXECUTION_ERROR")
        return 2
    print("G4_CREDENTIAL_OUTPUT_SCAN: " + ("FAIL" if scanner.leaked else "PASS"))
    print("G4_BROWSER_COMMAND: " + ("PASS" if code == 0 else "FAIL"))
    if scanner.leaked:
        return 1
    return 0 if code == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
