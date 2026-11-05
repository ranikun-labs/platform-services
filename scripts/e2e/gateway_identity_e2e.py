#!/usr/bin/env python3
"""Redacted cross-container Gateway-to-Identity contract checks for Issue #24."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.client import HTTPConnection
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import sys
import threading
import time


GATEWAY_URL = os.environ.get("PLATFORM_E2E_GATEWAY_URL", "http://gateway-app:8080").rstrip("/")
CORE_URL = os.environ.get("PLATFORM_E2E_CORE_URL", "http://platform-core-app:8081").rstrip("/")
FAKE_KAKAO_URL = os.environ.get("PLATFORM_E2E_FAKE_KAKAO_URL", "http://fake-kakao:8099").rstrip("/")
VALKEY_HOST = os.environ.get("PLATFORM_E2E_VALKEY_HOST", "valkey")
VALKEY_PORT = int(os.environ.get("PLATFORM_E2E_VALKEY_PORT", "6379"))
JWT_SECRET = os.environ.get("PLATFORM_E2E_JWT_SECRET", "")
LOGIN_ID = os.environ.get("PLATFORM_E2E_LOGIN_ID", "")
PASSWORD = os.environ.get("PLATFORM_E2E_PASSWORD", "")
FAKE_CODE = os.environ.get("PLATFORM_E2E_FAKE_KAKAO_CODE", "")
RUN_TOKEN = os.environ.get("PLATFORM_E2E_RUN_TOKEN", "")
SOURCE_COMMIT = os.environ.get("PLATFORM_E2E_SOURCE_COMMIT", "")
SOURCE_TREE = os.environ.get("PLATFORM_E2E_SOURCE_TREE", "")
COMPOSE_FILE_SHA256 = os.environ.get("PLATFORM_E2E_COMPOSE_FILE_SHA256", "")
CORE_IMAGE_ID = os.environ.get("PLATFORM_E2E_CORE_IMAGE_ID", "")
GATEWAY_IMAGE_ID = os.environ.get("PLATFORM_E2E_GATEWAY_IMAGE_ID", "")
ACCOUNT_ID = "00000000-0000-4000-8000-000000000024"
AUTH_OK_MESSAGE = "요청에 성공하였습니다."
CHECKS = []


class ContractFailure(AssertionError):
    pass


def record(name, status, **details):
    CHECKS.append({"name": name, "result": status, **details})


def require(condition, name, status=None, **details):
    if not condition:
        record(name, "FAIL", httpStatus=status, **details)
        raise ContractFailure(name)
    record(name, "PASS", httpStatus=status, **details)


def http(method, base_url, path, body=None, bearer=None, forwarded_for=None, timeout=12):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if bearer is not None:
        headers["Authorization"] = "Bearer " + bearer
    if forwarded_for is not None:
        headers["X-Forwarded-For"] = forwarded_for
    request = Request(base_url + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, response.headers, response.read()
    except HTTPError as response:
        return response.code, response.headers, response.read()
    except (URLError, TimeoutError, socket.timeout) as exc:
        raise RuntimeError(type(exc).__name__) from None


def json_body(response):
    try:
        return json.loads(response[2].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ContractFailure("expected_json_response") from None


def assert_envelope(response, expected_status, expected_message, name):
    status, headers, _body = response
    payload = json_body(response)
    require(status == expected_status, name + ".http", status, expected=expected_status)
    require(payload.get("status") == expected_status, name + ".envelope_status", status)
    require(payload.get("message") == expected_message, name + ".message", status)
    require("data" not in payload, name + ".no_data", status)
    require("application/json" in headers.get("Content-Type", ""), name + ".content_type", status)
    return payload


def assert_success(response, expected_keys, name):
    status, headers, _body = response
    payload = json_body(response)
    require(status == 200, name + ".http", status, expected=200)
    require(payload.get("status") == 200, name + ".envelope_status", status)
    require(payload.get("message") == AUTH_OK_MESSAGE, name + ".message", status)
    require(isinstance(payload.get("data"), dict), name + ".data_object", status)
    data = payload["data"]
    require(set(expected_keys).issubset(data), name + ".data_contract", status)
    require("application/json" in headers.get("Content-Type", ""), name + ".content_type", status)
    return data


def jwt_token(payload):
    header = {"alg": "HS256", "typ": "JWT"}
    parts = [b64url(json.dumps(value, separators=(",", ":")).encode()) for value in (header, payload)]
    signing_input = ".".join(parts).encode("ascii")
    signature = hmac.new(JWT_SECRET.encode(), signing_input, hashlib.sha256).digest()
    return signing_input.decode("ascii") + "." + b64url(signature)


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def expired_jwt():
    now = int(time.time())
    return jwt_token({"sub": ACCOUNT_ID, "iat": now - 120, "exp": now - 60})


def tampered_jwt():
    now = int(time.time())
    token = jwt_token({"sub": ACCOUNT_ID, "iat": now - 1, "exp": now + 300})
    header, claims, signature = token.split(".")
    changed = ("B" if signature[0] != "B" else "A") + signature[1:]
    return header + "." + claims + "." + changed


def redis_command(*parts):
    with socket.create_connection((VALKEY_HOST, VALKEY_PORT), timeout=3) as connection:
        connection.settimeout(3)
        encoded = [str(part).encode("utf-8") for part in parts]
        payload = b"*" + str(len(encoded)).encode() + b"\r\n"
        for part in encoded:
            payload += b"$" + str(len(part)).encode() + b"\r\n" + part + b"\r\n"
        connection.sendall(payload)
        return read_resp(connection)


def read_resp(connection):
    prefix = read_exact(connection, 1)
    line = read_line(connection)
    if prefix == b"+":
        return line.decode("utf-8")
    if prefix == b"-":
        raise RuntimeError("ValkeyCommandError")
    if prefix == b":":
        return int(line)
    if prefix == b"$":
        length = int(line)
        if length < 0:
            return None
        value = read_exact(connection, length)
        read_exact(connection, 2)
        return value.decode("utf-8", errors="replace")
    if prefix == b"*":
        count = int(line)
        if count < 0:
            return None
        return [read_resp(connection) for _ in range(count)]
    raise RuntimeError("ValkeyProtocolError")


def read_line(connection):
    value = bytearray()
    while not value.endswith(b"\r\n"):
        chunk = connection.recv(1)
        if not chunk:
            raise RuntimeError("ValkeyConnectionClosed")
        value.extend(chunk)
    return bytes(value[:-2])


def read_exact(connection, length):
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise RuntimeError("ValkeyConnectionClosed")
        result.extend(chunk)
    return bytes(result)


def command_calls(command_names):
    info = redis_command("INFO", "commandstats")
    counts = {name: 0 for name in command_names}
    for line in info.splitlines():
        match = re.match(r"cmdstat_([a-z0-9]+):calls=(\d+)", line)
        if match and match.group(1) in counts:
            counts[match.group(1)] = int(match.group(2))
    return counts


def login():
    response = http("POST", GATEWAY_URL, "/api/v1/auth/login", {"userId": LOGIN_ID, "password": PASSWORD})
    data = assert_success(response, {"accessToken", "refreshToken"}, "gateway.identity.login")
    require(bool(data["accessToken"]), "gateway.identity.login.access_token", 200)
    require(bool(data["refreshToken"]), "gateway.identity.login.refresh_token", 200)
    return data["accessToken"], data["refreshToken"]


def check_unauthorized_and_forbidden():
    response = http("POST", CORE_URL, "/api/v1/auth/logout")
    payload = json_body(response)
    require(response[0] == 401, "identity.unauthorized.http", response[0], expected=401)
    require(payload.get("status") == 401, "identity.unauthorized.contract_status", response[0])
    require(payload.get("error") == "Unauthorized", "identity.unauthorized.error", response[0])
    require(payload.get("message") == "인증이 필요합니다. 로그인 후 다시 시도해주세요.",
            "identity.unauthorized.message", response[0])
    require(payload.get("path") == "/api/v1/auth/logout", "identity.unauthorized.path", response[0])

    response = http("POST", GATEWAY_URL, "/api/v1/auth/logout")
    require(response[0] == 401 and response[2] == b"", "gateway.unauthorized", response[0], expected=401)

    response = http("GET", GATEWAY_URL, "/api/v1/internal")
    require(response[0] == 403 and response[2] == b"", "gateway.forbidden", response[0], expected=403)

    for label, token in (("expired", expired_jwt()), ("tampered", tampered_jwt())):
        response = http("POST", GATEWAY_URL, "/api/v1/auth/logout", bearer=token)
        require(response[0] == 401 and response[2] == b"", "gateway.jwt." + label, response[0], expected=401)


def check_refresh_rotation_and_revocation():
    access, refresh = login()
    rotated_response = http("POST", GATEWAY_URL, "/api/v1/auth/refresh", {"refreshToken": refresh})
    rotated = assert_success(rotated_response, {"accessToken", "refreshToken"}, "gateway.identity.refresh")
    require(rotated["refreshToken"] != refresh, "gateway.identity.refresh.refresh_rotated", 200)

    stale_response = http("POST", GATEWAY_URL, "/api/v1/auth/refresh", {"refreshToken": refresh})
    assert_envelope(stale_response, 404, "Refresh Token을 찾을 수 없습니다.", "gateway.identity.refresh_retry")

    logout_status, _headers, logout_body = http("POST", GATEWAY_URL, "/api/v1/auth/logout", bearer=access)
    require(logout_status == 204 and logout_body == b"", "gateway.identity.logout.valid_jwt", logout_status)
    revoked = http("POST", GATEWAY_URL, "/api/v1/auth/logout", bearer=access)
    require(revoked[0] == 401 and revoked[2] == b"", "gateway.jwt.revoked", revoked[0], expected=401)


def check_refresh_race():
    _access, refresh = login()
    barrier = threading.Barrier(3)

    def refresh_at_once():
        barrier.wait(timeout=10)
        return http("POST", GATEWAY_URL, "/api/v1/auth/refresh", {"refreshToken": refresh})

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(refresh_at_once) for _ in range(2)]
        barrier.wait(timeout=10)
        responses = [future.result(timeout=20) for future in futures]

    statuses = sorted(response[0] for response in responses)
    require(statuses == [200, 404], "gateway.identity.refresh_race.single_consumer", None,
            expected=[200, 404])
    stale = next(response for response in responses if response[0] == 404)
    assert_envelope(stale, 404, "Refresh Token을 찾을 수 없습니다.", "gateway.identity.refresh_race.retry")
    winner = next(response for response in responses if response[0] == 200)
    tokens = assert_success(winner, {"accessToken", "refreshToken"}, "gateway.identity.refresh_race.winner")
    require(tokens["refreshToken"] != refresh, "gateway.identity.refresh_race.rotated", 200)


def check_oauth_state_and_fake_provider():
    response = http(
        "POST",
        GATEWAY_URL,
        "/api/v1/auth/oauth/kakao/authorization",
        {"clientChannel": "WEB", "returnTo": "/"},
        forwarded_for="203.0.113.240",
    )
    data = assert_success(response, {"authorizationUrl"}, "gateway.oauth.authorization")
    require("state" not in data and "codeVerifier" not in data,
            "gateway.oauth.authorization.no_internal_state_fields", 200)
    authorization_url = data["authorizationUrl"]
    query = parse_qs(urlsplit(authorization_url).query)
    state_values = query.get("state", [])
    require(len(state_values) == 1 and bool(state_values[0]), "gateway.oauth.authorization.state_in_url", 200)
    state = state_values[0]
    require(urlsplit(authorization_url).hostname == "fake-kakao", "gateway.oauth.authorization.fake_provider", 200)

    ttl = redis_command("TTL", "oauth:state:" + state)
    require(isinstance(ttl, int) and 0 < ttl <= 300, "identity.oauth.state.ttl", None,
            ttlSeconds=ttl if isinstance(ttl, int) else None)

    before = http("GET", FAKE_KAKAO_URL, "/__e2e/counters")
    require(before[0] == 200, "fake_provider.counters.ready", before[0])
    before_counts = json.loads(before[2].decode("utf-8"))

    callback_body = {"code": FAKE_CODE, "state": state}
    barrier = threading.Barrier(3)

    def exchange_at_once(client_ip):
        barrier.wait(timeout=10)
        return http(
            "POST",
            GATEWAY_URL,
            "/api/v1/auth/oauth/kakao/exchange",
            callback_body,
            forwarded_for=client_ip,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(exchange_at_once, "203.0.113.241"),
            executor.submit(exchange_at_once, "203.0.113.242"),
        ]
        barrier.wait(timeout=10)
        responses = [future.result(timeout=20) for future in futures]

    statuses = sorted(response[0] for response in responses)
    require(statuses == [401, 409], "gateway.oauth.state_getdel_race.single_consumer", None,
            expected=[401, 409])
    assert_envelope(next(response for response in responses if response[0] == 401), 401,
                    "유효하지 않거나 만료된 인증 요청입니다.", "gateway.oauth.state_getdel_race.replay")
    assert_envelope(next(response for response in responses if response[0] == 409), 409,
                    "연결된 Carelog 계정이 없습니다.", "gateway.oauth.fake_provider.unlinked_account")

    after = http("GET", FAKE_KAKAO_URL, "/__e2e/counters")
    require(after[0] == 200, "fake_provider.counters.after_callback", after[0])
    after_counts = json.loads(after[2].decode("utf-8"))
    require(after_counts.get("tokenExchanges", 0) - before_counts.get("tokenExchanges", 0) == 1,
            "fake_provider.single_token_exchange", 200)
    require(after_counts.get("userInfo", 0) - before_counts.get("userInfo", 0) == 1,
            "fake_provider.single_user_info", 200)

    exists = redis_command("EXISTS", "oauth:state:" + state)
    require(exists == 0, "identity.oauth.state.deleted_after_consume", None)
    counts = command_calls({"getdel": 0})
    require(counts["getdel"] >= 2, "identity.oauth.state.getdel_command", None,
            getdelCalls=counts["getdel"])


def check_gateway_rate_limiter_lua():
    client_ip = "203.0.113.243"

    def authorize():
        return http(
            "POST",
            GATEWAY_URL,
            "/api/v1/auth/oauth/kakao/authorization",
            {"clientChannel": "WEB", "returnTo": "/"},
            forwarded_for=client_ip,
        )

    with ThreadPoolExecutor(max_workers=12) as executor:
        responses = list(executor.map(lambda _index: authorize(), range(12)))
    statuses = [response[0] for response in responses]
    require(all(status in (200, 429) for status in statuses), "gateway.rate_limit.allowed_status_contract", None)
    require(200 in statuses and 429 in statuses, "gateway.rate_limit.burst_then_deny", None,
            allowed=statuses.count(200), denied=statuses.count(429))
    for response in responses:
        if response[0] == 200:
            assert_success(response, {"authorizationUrl"}, "gateway.rate_limit.allowed_response")
        else:
            require(response[2] == b"", "gateway.rate_limit.denied_response", 429)

    counts = command_calls({"eval": 0, "evalsha": 0})
    lua_calls = counts["eval"] + counts["evalsha"]
    require(lua_calls > 0, "gateway.rate_limit.valkey_lua_execution", None,
            evalCalls=counts["eval"], evalshaCalls=counts["evalsha"])


def check_valkey_failure_closed():
    gateway_response = http(
        "POST",
        GATEWAY_URL,
        "/api/v1/auth/oauth/kakao/authorization",
        {"clientChannel": "WEB", "returnTo": "/"},
        forwarded_for="203.0.113.244",
        timeout=20,
    )
    require(gateway_response[0] == 503 and gateway_response[2] == b"",
            "gateway.rate_limit.valkey_failure_closed", gateway_response[0], expected=503)

    core_response = http(
        "POST",
        CORE_URL,
        "/api/v1/auth/oauth/kakao/authorization",
        {"clientChannel": "WEB", "returnTo": "/"},
        timeout=75,
    )
    assert_envelope(core_response, 503, "OAuth 인증 요청 저장소를 사용할 수 없습니다.",
                    "identity.oauth.valkey_failure")


def check_postgres_failure():
    response = http(
        "POST",
        GATEWAY_URL,
        "/api/v1/auth/login",
        {"userId": LOGIN_ID, "password": PASSWORD},
        timeout=75,
    )
    assert_envelope(response, 500, "서버에 예상치 못한 오류가 발생했습니다.",
                    "gateway.identity.postgres_failure")


def write_redacted_evidence(phase, result, error_type=None, evidence_dir=None, run_token=None):
    token = run_token or RUN_TOKEN
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("E2E evidence run token is invalid")
    output_dir = evidence_dir or os.environ.get("PLATFORM_E2E_EVIDENCE_DIR", "/evidence")
    evidence = {
        "evidenceVersion": 3,
        "issue": 24,
        "runToken": token,
        "sourceCommit": SOURCE_COMMIT,
        "sourceTree": SOURCE_TREE,
        "composeFileSha256": COMPOSE_FILE_SHA256,
        "images": {
            "platformCoreImageId": CORE_IMAGE_ID,
            "gatewayImageId": GATEWAY_IMAGE_ID,
        },
        "phase": phase,
        "result": result,
        "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "checks": CHECKS,
        "summary": {
            "passed": sum(item["result"] == "PASS" for item in CHECKS),
            "failed": sum(item["result"] == "FAIL" for item in CHECKS),
        },
    }
    if error_type:
        evidence["errorType"] = error_type

    path = os.path.join(output_dir, "issue-24-gateway-identity-" + phase + "-" + token + ".json")
    temporary = path + "." + token + ".tmp"
    temporary_created = False
    try:
        os.makedirs(output_dir, exist_ok=True)
        file_descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        temporary_created = True
        with os.fdopen(file_descriptor, "w", encoding="utf-8") as output:
            json.dump(evidence, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary, path)
    except OSError:
        if temporary_created:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        raise

    print(json.dumps(evidence, ensure_ascii=False, separators=(",", ":")))
    return path


def persist_evidence(phase, result, error_type=None):
    try:
        write_redacted_evidence(phase, result, error_type)
    except OSError as failure:
        print("E2E evidence persistence failed (" + type(failure).__name__ + ")", file=sys.stderr)
        return False
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("functional", "valkey-failure", "postgres-failure"),
                        default="functional")
    phase = parser.parse_args().phase
    if not all((JWT_SECRET, LOGIN_ID, PASSWORD, FAKE_CODE)):
        print("E2E configuration missing", file=sys.stderr)
        return 2
    if (not re.fullmatch(r"[0-9a-f]{32}", RUN_TOKEN)
            or not re.fullmatch(r"[0-9a-f]{40}", SOURCE_COMMIT)
            or not re.fullmatch(r"[0-9a-f]{40}", SOURCE_TREE)
            or not re.fullmatch(r"[0-9a-f]{64}", COMPOSE_FILE_SHA256)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", CORE_IMAGE_ID)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", GATEWAY_IMAGE_ID)):
        print("E2E source/image evidence binding missing or invalid", file=sys.stderr)
        return 2
    try:
        if phase == "functional":
            check_unauthorized_and_forbidden()
            invalid = http("POST", GATEWAY_URL, "/api/v1/auth/login",
                           {"userId": LOGIN_ID, "password": "WrongPassword123!"})
            assert_envelope(invalid, 401, "아이디 또는 비밀번호가 일치하지 않습니다.",
                            "gateway.identity.invalid_credentials")
            check_refresh_rotation_and_revocation()
            check_refresh_race()
            check_oauth_state_and_fake_provider()
            check_gateway_rate_limiter_lua()
        elif phase == "valkey-failure":
            check_valkey_failure_closed()
        else:
            check_postgres_failure()
    except Exception as failure:
        if not CHECKS or CHECKS[-1].get("result") != "FAIL":
            record("phase.exception", "FAIL", errorType=type(failure).__name__)
        if not persist_evidence(phase, "FAIL", type(failure).__name__):
            return 3
        return 1
    if not persist_evidence(phase, "PASS"):
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
