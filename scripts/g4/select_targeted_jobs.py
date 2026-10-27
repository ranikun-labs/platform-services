#!/usr/bin/env python3
"""RPL-55 product test selector. Carelog changes require their own repository CI."""
import argparse
import sys

def select(paths, manual=False):
    out = {"gateway": False, "identity": False, "carelog": False}
    if manual or any(p in {
        ".github/workflows/audit-g4-targeted-feature.yml",
        "scripts/g4/select_targeted_jobs.py",
        "scripts/g4/test_select_targeted_jobs.py",
    } for p in paths):
        return dict.fromkeys(out, True)
    for p in paths:
        if p.startswith("gateway-app/src/") or p in {
            "gateway-app/build.gradle.kts", "gateway-app/settings.gradle.kts",
            "gateway-app/Dockerfile", "gateway-app/Dockerfile.e2e",
        }:
            out["gateway"] = True
        if p.startswith("platform-core/identity/") or p.startswith(
            "platform-core/app/src/"
        ) or p.startswith("platform-core/app/Dockerfile") or p in {
            "platform-core/app/build.gradle.kts", "platform-core/build.gradle.kts",
            "platform-core/settings.gradle.kts",
        }:
            out["identity"] = True
        if p in {
            "build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat",
            "docker-compose.yml", "docker-compose.identity-e2e.yml",
            "docker-compose.g4-e2e.yml",
        } or p.startswith("gradle/"):
            out["gateway"] = True
            out["identity"] = True
        if p.startswith("scripts/g4/"):
            out = dict.fromkeys(out, True)
    return out

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    paths = [line.strip() for line in sys.stdin if line.strip()]
    for key, value in select(paths, args.all).items():
        print(f"{key}={str(value).lower()}")
