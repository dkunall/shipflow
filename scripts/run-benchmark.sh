#!/usr/bin/env bash
# Reproducible ShipFlow benchmark against a local PostgreSQL (database shipflow_bench must exist).
# Usage: scripts/run-benchmark.sh [extra -Dbench.* overrides]
#   e.g. scripts/run-benchmark.sh -Dbench.threads=50,100,200 -Dbench.runs=3 -Dbench.runSec=10
# DB password is read from env BENCH_DB_PASSWORD (local default: postgres). Results: docs/benchmark-results/*.md
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -q -DskipTests test-compile
mvn -B -q -Pbenchmark -DskipTests "$@" test-compile exec:exec
