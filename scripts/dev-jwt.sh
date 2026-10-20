#!/usr/bin/env bash
# Mint a short-lived HS256 dev JWT for local testing.
#   usage: JWT_SECRET=... scripts/dev-jwt.sh ROLE [merchant-uuid] [ttl-seconds]
#   ROLE: MERCHANT | OPS | ADMIN | RIDER   (MERCHANT requires merchant-uuid)
# The secret is read ONLY from the JWT_SECRET environment variable (never an argument, so it does not show up in
# `ps` or shell history) and must equal the one the app runs with. Optional JWT_ISSUER is added as `iss`.
set -euo pipefail
ROLE="${1:?usage: JWT_SECRET=... $0 ROLE [merchant-uuid] [ttl-seconds]}"
MERCHANT="${2:-}"
TTL="${3:-3600}"
: "${JWT_SECRET:?export JWT_SECRET (same value the app uses)}"
if [[ "$ROLE" == "MERCHANT" && -z "$MERCHANT" ]]; then
  echo "MERCHANT tokens need a merchant uuid" >&2; exit 2
fi
ROLE="$ROLE" MERCHANT="$MERCHANT" TTL="$TTL" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
claims = {"sub": "dev-" + os.environ["ROLE"].lower(), "roles": [os.environ["ROLE"]], "iat": now,
          "exp": now + int(os.environ["TTL"])}
if os.environ.get("MERCHANT"):
    claims["merchant_id"] = os.environ["MERCHANT"]
if os.environ.get("JWT_ISSUER"):
    claims["iss"] = os.environ["JWT_ISSUER"]
signing = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()) + "." + b64(json.dumps(claims).encode())
sig = hmac.new(os.environ["JWT_SECRET"].encode(), signing.encode(), hashlib.sha256).digest()
print(signing + "." + b64(sig))
PY
