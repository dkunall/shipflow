#!/usr/bin/env bash
# End-to-end demo against the docker compose stack (app :7300, postgres :55460).
#   scripts/demo.sh            # builds, starts, seeds, runs the walkthrough, leaves the stack running
#   KEEP=0 scripts/demo.sh     # also stops the stack at the end (shows graceful shutdown)
# JWT_SECRET: taken from the environment, or a random one is generated for this run (never printed).
set -euo pipefail
cd "$(dirname "$0")/.."

export JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 48)}"
export JWT_ISSUER="${JWT_ISSUER:-shipflow-local}"
APP_PORT="${APP_PORT:-7300}"
BASE="http://127.0.0.1:$APP_PORT"
H="$BASE/api/v1"
M=11111111-1111-1111-1111-111111111111

step() { printf '\n== %s\n' "$*"; }
j() { jq -c "$@"; }

step "build + start (docker compose project: shipflow)"
docker compose up -d --build --wait --quiet-pull 2>&1 | grep -vE '^\s*$' | tail -3 || true
for _ in $(seq 1 60); do
  curl -sf "$BASE/actuator/health/readiness" >/dev/null && break; sleep 2
done
curl -s "$BASE/actuator/health/readiness"; echo
curl -s "$BASE/actuator/health/liveness"; echo

step "seed a small Dubai network (3 carriers, 3 pincodes, 2 riders)"
docker compose exec -T postgres psql -q -U postgres -d shipflow < scripts/seed-dev.sql

MT=$(scripts/dev-jwt.sh MERCHANT $M); OPS=$(scripts/dev-jwt.sh OPS); ADM=$(scripts/dev-jwt.sh ADMIN)
RUN=$(date +%s)

book() { # key ref pincode payment
  local cod=""
  [[ "$4" == "COD" ]] && cod='"codAmount":{"amount":"250.00","currency":"AED"},'
  curl -s -XPOST "$H/shipments" -H "Authorization: Bearer $MT" -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
    -H "X-Correlation-Id: demo-$1" -d "{
    \"merchantOrderRef\":\"$2\",\"originHubCode\":\"DXB-HUB\",
    \"consignee\":{\"name\":\"Aisha Khan\",\"phone\":\"+971501234567\",\"addressLine1\":\"Villa 12, Street 4B, Al Barsha 2\",
                 \"landmark\":\"near Mall of the Emirates\",\"city\":\"Dubai\",\"pincode\":\"$3\"},
    \"weightGrams\":1200,\"paymentMode\":\"$4\",$cod
    \"declaredValue\":{\"amount\":\"250.00\",\"currency\":\"AED\"}}"
}

step "1. book a COD shipment"
R=$(book "ord-$RUN-1" "ORD-$RUN-1" DXB-001 COD)
echo "$R" | j '{id,status,trackingNumber,carrier:.carrier.code,awb,cost:.shippingCost,risk:{score:.risk.score,band:.risk.band},promisedDate}'
ID=$(echo "$R" | jq -r .id); TRK=$(echo "$R" | jq -r .trackingNumber); CARRIER=$(echo "$R" | jq -r .carrier.code)

step "2. replay the same Idempotency-Key -> same shipment, no second booking"
book "ord-$RUN-1" "ORD-$RUN-1" DXB-001 COD | j '{id,trackingNumber}'
echo "same id: $([ "$(book "ord-$RUN-1" "ORD-$RUN-1" DXB-001 COD | jq -r .id)" = "$ID" ] && echo yes || echo NO)"

step "3. same key, different body -> rejected"
book "ord-$RUN-1" "ORD-$RUN-OTHER" DXB-002 COD | j '{status,code,title}'

step "4. validation error -> RFC 7807"
curl -s -XPOST "$H/shipments" -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: bad-$RUN" -d '{"merchantOrderRef":"","weightGrams":-1}' | j '{status,code,title,errors:(.errors // .fieldErrors | length)}'

step "5. why this carrier? (filters + weighted rule scores)"
curl -s "$H/shipments/$ID/allocation" -H "Authorization: Bearer $MT" | j '.decisions[0].explanation | {summary, ranked:[.ranked[] | {carrier,totalScore,price,slaDays}], rejected}'

step "6. bulk CSV upload (2 rows)"
printf 'merchant_order_ref,origin_hub,name,phone,address_line1,city,pincode,weight_g,payment_mode,declared_value,currency\nCSV-%s-1,DXB-HUB,Omar,+971501112233,Tower 3 Marina,Dubai,DXB-002,800,PREPAID,99.00,AED\nCSV-%s-2,DXB-HUB,Sara,+971501112244,Villa 7 Jumeirah,Dubai,DXB-003,1500,PREPAID,45.00,AED\n' "$RUN" "$RUN" |
  curl -s -XPOST "$H/shipments/bulk" -H "Authorization: Bearer $MT" -H 'Content-Type: text/csv' --data-binary @- | j '{total,created,duplicates,invalid,rows:[.rows[] | {line,status}]}'

step "7. scans PICKED_UP -> OUT_FOR_DELIVERY -> DELIVERED, plus a duplicate"
for s in PICKED_UP OUT_FOR_DELIVERY DELIVERED DELIVERED; do
  curl -s -XPOST "$H/scans" -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
    -d "{\"carrierCode\":\"$CARRIER\",\"carrierEventId\":\"ev-$RUN-$s\",\"trackingNumber\":\"$TRK\",\"eventCode\":\"$s\",\"occurredAt\":\"$(date -u +%FT%TZ)\"}" | j -c '{eventCode:"'$s'", result:(.result // .outcome // .status), shipmentStatus}'
done

step "8. public tracking page (no auth, no PII)"
curl -s "$H/tracking/$TRK" | j '{status,carrier,destinationCity,promisedDate,events:[.events[].status]}'

step "9. COD remittance + reconciliation"
curl -s -XPOST "$H/cod/remittances" -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d "{\"carrierCode\":\"$CARRIER\",\"remittanceRef\":\"R-$RUN\",\"currency\":\"AED\",\"receivedAt\":\"$(date -u +%FT%TZ)\",\"lines\":[{\"trackingNumber\":\"$TRK\",\"amount\":\"250.00\"}]}" | j .
curl -s -XPOST "$H/cod/reconciliations" -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d "{\"carrierCode\":\"$CARRIER\",\"from\":\"$(date -u -d '-1 day' +%FT%TZ)\",\"to\":\"$(date -u -d '+1 day' +%FT%TZ)\"}" | j 'del(.lines)'

step "10. role check: a MERCHANT token on an ADMIN endpoint -> 403"
curl -s -o /dev/null -w '%{http_code}\n' "$H/admin/carriers/$CARRIER/scorecard?zone=DXB-Z1" -H "Authorization: Bearer $MT"
curl -s -o /dev/null -w 'no token: %{http_code}\n' "$H/shipments/$ID"

step "11. transient carrier errors are retried (FASTX + CHEAP flaky: first call per booking fails with 503)"
docker compose exec -T postgres psql -q -U postgres -d shipflow -c \
  "UPDATE carrier SET active = false WHERE code = 'OWNF'" >/dev/null 2>&1 || true
for c in FASTX CHEAP; do
  curl -s -XPUT "$H/admin/simulator/carriers/$c" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"mode":"FLAKY"}'
done
for i in 1 2 3; do book "flaky-$RUN-$i" "FLAKY-$RUN-$i" DXB-002 PREPAID | j '{status,carrier:.carrier.code,awb}'; done
curl -s "$BASE/actuator/prometheus" -H "Authorization: Bearer $ADM" | grep -E '^shipflow_carrier_retries' || echo "(no retry metric)"

step "12. FASTX hard down -> breaker opens, bookings fail over"
curl -s -XPUT "$H/admin/simulator/carriers/FASTX" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"mode":"FAIL"}'
curl -s -XPUT "$H/admin/simulator/carriers/CHEAP" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"mode":"OK"}'
for i in $(seq 1 7); do book "fail-$RUN-$i" "FAIL-$RUN-$i" DXB-003 PREPAID | jq -r '.carrier.code // .code'; done | sort | uniq -c
curl -s "$H/admin/carriers/FASTX/scorecard?zone=DXB-Z1" -H "Authorization: Bearer $ADM" | j '{carrier,circuit}'
curl -s -XPUT "$H/admin/simulator/carriers/FASTX" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{"mode":"OK"}'
docker compose exec -T postgres psql -q -U postgres -d shipflow -c "UPDATE carrier SET active = true WHERE code = 'OWNF'" >/dev/null 2>&1 || true

step "13. correlation id: echoed back as a header and attached to every log line of the request"
curl -s -o /dev/null -D - "$H/tracking/$TRK" -H "X-Correlation-Id: demo-trace-$RUN" | grep -i '^x-correlation-id'
docker compose logs --no-log-prefix app 2>/dev/null | grep "demo-flaky-$RUN-1" | head -2 | jq -c '{level,correlationId,message}' || true

step "14. a few metrics"
curl -s "$BASE/actuator/prometheus" -H "Authorization: Bearer $ADM" |
  grep -E '^(shipflow_[a-z_]+_total|http_server_requests_seconds_count\{.*uri="/api/v1/shipments".*\})' | head -12

step "15. load: 400 bookings, 16 concurrent clients (single box: client + app + postgres together)"
TOKEN="$MT" python3 scripts/loadtest.py "$H" 400 16 "LT-$RUN"

if [[ "${KEEP:-1}" == "0" ]]; then
  step "stop (graceful shutdown)"
  docker compose stop app >/dev/null 2>&1
  docker compose logs app 2>/dev/null | grep -iE 'graceful|shutdown' | tail -3 | jq -c '{message}' 2>/dev/null || true
  docker compose down >/dev/null 2>&1
fi
