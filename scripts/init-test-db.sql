-- Extra databases next to `shipflow`: integration tests (shipflow_test) and the benchmark (shipflow_bench).
-- Flyway creates every table; nothing else is needed.
CREATE DATABASE shipflow_test;
CREATE DATABASE shipflow_bench;
