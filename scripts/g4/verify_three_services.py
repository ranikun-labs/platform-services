#!/usr/bin/env python3
"""Real three-process G4 integration checks. Fixed internal Docker services only.

No live Kakao, external hosts, production credentials, token dumps, or DB writes outside
the disposable Compose network. Customer mutations below target disposable test DB only.
"""
import base64
import hashlib
import hmac
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

GATEWAY = os.environ["G4_GATEWAY_URL"]
CARELOG = os.environ["G4_CARELOG_URL"]
JWT_SECRET = os.environ["G4_JWT_SECRET"]
SERVICE_SECRET = os.environ["G4_SERVICE_SECRET"]
ACCOUNT_A = "00000000-0000-4000-8000-000000000024"
ACCOUNT_B = "00000000-0000-4000-8000-000000000026"
ORG_A = "00000000-0000-4000-8000-000000000025"
ORG_B = "00000000-0000-4000-8000-000000000027"
NO_REDIRECT = build_opener(ProxyHandler({}), type("NoRedirect", (HTTPRedirectHandler,), {
    "redirect_request": lambda self, req, fp, code, msg, headers, newurl: None
})())
results = []

def req(method, origin, path, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Accept": "application/json"}
    if data is not None:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    request = Request(origin + path, headers=h, data=data, method=method)
    try:
        with NO_REDIRECT.open(request, timeout=8) as r:
            return r.status, r.read(1048576)
    except HTTPError as e:
        with e:
            return e.code, e.read(1048576)

def check(name, condition, expected, observed):
    results.append({"check": name, "status": "PASS" if condition else "FAIL",
                    "expected": str(expected), "observed": str(observed)})
    if not condition:
        raise AssertionError(name)

def json_data(response):
    return json.loads(response[1])["data"]

def sign(payload):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()
    header = enc({"alg": "HS256", "typ": "JWT"})
    content = enc(payload)
    signature = base64.urlsafe_b64encode(hmac.new(JWT_SECRET.encode(), (header+"."+content).encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return header+"."+content+"."+signature

def run():
    # Real Spring Boot login through Gateway -> Identity -> Carelog internal projection.
    status, body = req("POST", GATEWAY, "/api/v1/auth/login",
                       {"userId": "issue24-e2e-user", "password": "Password123!"})
    check("G4-01-login-through-real-product-projection-A", status == 200, 200, status)
    login_a = json.loads(body)["data"]
    token_a = login_a["accessToken"]
    check("G4-01-A-identity-access-token", bool(token_a), "non-empty", "present" if token_a else "missing")
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_a)
    check("G4-01-real-product-customer-api-A", status == 200, 200, status)
    customers = json_data((status, body))
    check("G4-01-customer-contract-array-A", isinstance(customers, list), "array", type(customers).__name__)

    status, body = req("POST", GATEWAY, "/api/v1/users/customers",
                       {"displayName": "Synthetic Customer A", "customerMemo": "local disposable"},
                       token=token_a)
    check("G4-05-A-create-own-customer", status == 201, 201, status)
    customer_a_id = json_data((status, body))["publicId"]
    status, body = req("GET", GATEWAY, "/api/v1/users/customers/" + customer_a_id, token=token_a)
    check("G4-05-A-owner-can-read", status == 200 and json_data((status, body)).get("publicId") == customer_a_id,
          "200 exact Customer ID", status)
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_a)
    customers_a = json_data((status, body))
    check("G4-05-A-list-contains-only-own-customer",
          status == 200 and isinstance(customers_a, list) and len(customers_a) == 1
          and customers_a[0].get("publicId") == customer_a_id,
          "A customer only", status)

    # External trusted-header spoofing must not change the verified owner view.
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_a, headers={
        "X-User-Id": ACCOUNT_B, "X-Organization-Id": ORG_B, "X-Role": "MANAGER",
        "X-Public-Id": ACCOUNT_B, "X-Gateway-Secret": "spoofed",
        "X-Service-Id": "spoofed", "X-Service-Secret": "spoofed"})
    check("G4-02-trusted-header-sanitization", status == 200 and json_data((status, body)) == customers_a,
          "200 with identical owner scope", status)

    for case, provided in [("missing", None), ("malformed", "not.a.jwt")]:
        status, _ = req("GET", GATEWAY, "/api/v1/users/customers", token=provided)
        check("G4-03-"+case, status == 401, 401, status)

    # Signed but malformed claims must be rejected by the real Product auth filter.
    bad_claims = [
        ("invalid-organization-uuid", {"sub": ACCOUNT_A, "organizationId": "not-a-uuid",
                                      "publicId": ACCOUNT_A, "role": "MANAGER"}),
        ("missing-public-id", {"sub": ACCOUNT_A, "organizationId": ORG_A,
                               "role": "MANAGER"}),
    ]
    for label, claims in bad_claims:
        claims.update({"iat": int(time.time())-1, "exp": int(time.time())+600})
        code, _ = req("GET", GATEWAY, "/api/v1/users/customers", token=sign(claims))
        check("G4-04-"+label, code == 403, 403, code)

    status, _ = req("GET", GATEWAY, "/api/v1/internal/identity/claims/"+ACCOUNT_A, token=token_a)
    check("G4-08-public-internal-route-block", status == 403, 403, status)

    # Internal product projection is actually hosted by Carelog, not a fake stub.
    endpoint = "/api/v1/internal/identity/claims/"+ACCOUNT_A
    service_headers = {"X-Service-Id": "platform-identity", "X-Service-Secret": SERVICE_SECRET}
    status, body = req("GET", CARELOG, endpoint, headers=service_headers)
    claims = json.loads(body) if status == 200 else {}
    check("G4-06-real-carelog-projection", status == 200 and claims.get("organizationId") == ORG_A
          and claims.get("role") == "MANAGER" and claims.get("publicId") == ACCOUNT_A
          and set(claims) == {"organizationId", "role", "publicId"},
          "200 with three exact claims", status)
    status, _ = req("GET", CARELOG, endpoint, headers={"X-Service-Id":"platform-identity","X-Service-Secret":"incorrect"})
    check("G4-07-wrong-service-secret", status == 403, 403, status)
    status, _ = req("GET", CARELOG, "/api/v1/internal/identity/claims/"+ACCOUNT_B, headers=service_headers)
    check("G4-07-unknown-account", status == 404, 404, status)

    # B uses a separately seeded Identity account and a real Identity-issued session.
    status, body = req("POST", GATEWAY, "/api/v1/auth/login",
                       {"userId": "issue24-e2e-user-b", "password": "Password123!"})
    check("G4-09-B-login-through-identity", status == 200, 200, status)
    login_b = json.loads(body)["data"]
    token_b = login_b.get("accessToken")
    refresh_b = login_b.get("refreshToken")
    check("G4-09-B-identity-session-contract", bool(token_b) and bool(refresh_b),
          "access and refresh present", "present" if token_b and refresh_b else "missing")

    # Login itself resolves B's product-owned organization/public ID through Carelog.
    endpoint_b = "/api/v1/internal/identity/claims/" + ACCOUNT_B
    status, body = req("GET", CARELOG, endpoint_b, headers=service_headers)
    claims_b = json.loads(body) if status == 200 else {}
    check("G4-06-real-carelog-projection-B", status == 200
          and claims_b.get("organizationId") == ORG_B
          and claims_b.get("role") == "MANAGER"
          and claims_b.get("publicId") == ACCOUNT_B
          and set(claims_b) == {"organizationId", "role", "publicId"},
          "200 with B's three exact claims", status)

    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_b)
    customers_b_before = json_data((status, body)) if status == 200 else None
    check("G4-09-B-customer-api", status == 200 and isinstance(customers_b_before, list),
          "200 array", status)
    check("G4-09-B-cannot-list-A-customer",
          all(item.get("publicId") != customer_a_id for item in customers_b_before),
          "A customer absent from B's list", "isolated" if customers_b_before is not None else "unavailable")

    status, body = req("POST", GATEWAY, "/api/v1/users/customers",
                       {"displayName": "Synthetic Customer B", "customerMemo": "local disposable"},
                       token=token_b)
    check("G4-09-B-create-own-customer", status == 201, 201, status)
    customer_b_id = json_data((status, body))["publicId"]
    status, body = req("GET", GATEWAY, "/api/v1/users/customers/" + customer_b_id, token=token_b)
    check("G4-09-B-owner-can-read", status == 200 and json_data((status, body)).get("publicId") == customer_b_id,
          "200 exact Customer ID", status)
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_b)
    customers_b = json_data((status, body)) if status == 200 else None
    check("G4-09-B-list-contains-only-own-customer",
          status == 200 and isinstance(customers_b, list) and len(customers_b) == 1
          and customers_b[0].get("publicId") == customer_b_id,
          "B customer only", status)

    status, _ = req("GET", GATEWAY, "/api/v1/users/customers/" + customer_a_id, token=token_b)
    check("G4-09-B-cannot-read-A", status == 404, 404, status)
    status, _ = req("GET", GATEWAY, "/api/v1/users/customers/" + customer_b_id, token=token_a)
    check("G4-09-A-cannot-read-B", status == 404, 404, status)
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_a)
    check("G4-09-A-list-isolated-after-B-create",
          status == 200 and json_data((status, body)) == customers_a,
          "A list unchanged after B create", status)
    # Recheck header spoofing after B's real customer exists.
    status, body = req("GET", GATEWAY, "/api/v1/users/customers", token=token_a, headers={
        "X-User-Id": ACCOUNT_B, "X-Organization-Id": ORG_B, "X-Role": "MANAGER",
        "X-Public-Id": ACCOUNT_B, "X-Gateway-Secret": "spoofed",
        "X-Service-Id": "spoofed", "X-Service-Secret": "spoofed"})
    check("G4-02-existing-foreign-customer-spoof-proof",
          status == 200 and json_data((status, body)) == customers_a,
          "A remains isolated after B owns a customer", status)

    # B refresh rotation rejects the stale token; logout revokes access and refresh.
    status, body = req("POST", GATEWAY, "/api/v1/auth/refresh", {"refreshToken": refresh_b})
    check("G4-10-B-refresh-rotation", status == 200, 200, status)
    rotated_b = json_data((status, body))
    access_b_rotated = rotated_b.get("accessToken")
    refresh_b_rotated = rotated_b.get("refreshToken")
    check("G4-10-B-refresh-pair-present", bool(access_b_rotated) and bool(refresh_b_rotated),
          "access and refresh present", "present" if access_b_rotated and refresh_b_rotated else "missing")
    check("G4-10-B-refresh-token-rotated", refresh_b_rotated != refresh_b,
          "new refresh token", "rotated" if refresh_b_rotated != refresh_b else "unchanged")

    status, _ = req("POST", GATEWAY, "/api/v1/auth/refresh", {"refreshToken": refresh_b})
    check("G4-10-B-stale-refresh-rejected", status == 404, 404, status)
    status, body = req("POST", GATEWAY, "/api/v1/auth/logout", token=access_b_rotated)
    check("G4-10-B-logout", status == 204 and body == b"", "204 empty", status)
    status, _ = req("GET", GATEWAY, "/api/v1/users/customers", token=access_b_rotated)
    check("G4-10-B-access-revoked-after-logout", status == 401, 401, status)
    status, _ = req("POST", GATEWAY, "/api/v1/auth/refresh", {"refreshToken": refresh_b_rotated})
    check("G4-10-B-refresh-revoked-after-logout", status == 404, 404, status)

def main():
    for attempt in range(40):
        try:
            status, _ = req("GET", CARELOG, "/api/v1/users/customers")
            break
        except (URLError, ConnectionError, TimeoutError, OSError):
            time.sleep(3)
    else:
        print(json.dumps({"verdict":"BLOCKED_ENV","reason":"Carelog listener unavailable"}))
        return 2
    try:
        run()
    except Exception as e:
        # Do not log response bodies, headers, or credentials.
        print(json.dumps({"verdict":"FAIL","errorType":type(e).__name__,
                          "checks":results}, separators=(",", ":")))
        return 1
    print(json.dumps({"verdict":"G4_THREE_PROCESS_NONPROD_PARTIAL_PASS",
                      "scope":"real Spring Gateway/Core/Carelog, synthetic data; not Cutover",
                      "checks":results}, separators=(",", ":")))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
