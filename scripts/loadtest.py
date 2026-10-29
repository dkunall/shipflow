#!/usr/bin/env python3
"""Tiny closed-loop load generator for POST /shipments (stdlib only).

usage: TOKEN=... scripts/loadtest.py BASE_URL REQUESTS CONCURRENCY [prefix]
Prints throughput and latency percentiles. These are single-machine numbers: client, app and Postgres share one box.
"""
import json, os, statistics, sys, threading, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

base, total, conc = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
prefix = sys.argv[4] if len(sys.argv) > 4 else "LT"
token = os.environ["TOKEN"]
pins = ["DXB-001", "DXB-002", "DXB-003"]
lat, codes, lock = [], {}, threading.Lock()


def one(i):
    body = {"merchantOrderRef": f"{prefix}-{i}", "originHubCode": "DXB-HUB",
            "consignee": {"name": "Load Test", "phone": "+97150%07d" % i, "addressLine1": f"Building {i}, Street 9",
                          "city": "Dubai", "pincode": pins[i % 3]},
            "weightGrams": 500 + (i % 7) * 300, "paymentMode": "PREPAID",
            "declaredValue": {"amount": "120.00", "currency": "AED"}}
    req = urllib.request.Request(base + "/shipments", json.dumps(body).encode(), method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": f"{prefix}-{i}"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            code = r.status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception:
        code = "ERR"
    dt = (time.perf_counter() - t0) * 1000
    with lock:
        lat.append(dt)
        codes[code] = codes.get(code, 0) + 1


t0 = time.perf_counter()
with ThreadPoolExecutor(conc) as ex:
    list(ex.map(one, range(total)))
wall = time.perf_counter() - t0
q = statistics.quantiles(lat, n=100)
print(f"requests={total} concurrency={conc} wall={wall:.2f}s throughput={total / wall:.1f} req/s")
print(f"latency ms: p50={q[49]:.1f} p95={q[94]:.1f} p99={q[98]:.1f} max={max(lat):.1f}")
print("status codes:", dict(sorted(codes.items(), key=str)))
