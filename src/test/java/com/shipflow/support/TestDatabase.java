package com.shipflow.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Resolves the PostgreSQL used by integration tests, in this order:
 * <ol>
 *   <li>Testcontainers (postgres:17-alpine) when a Docker daemon is available;</li>
 *   <li>external DB from env/system properties TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD;</li>
 *   <li>a local default jdbc:postgresql://localhost:5432/shipflow_test (postgres/postgres) if reachable.</li>
 * </ol>
 * If none is reachable, {@link #get()} returns null and the integration tests are SKIPPED (not failed).
 * H2 is intentionally never used: the engine relies on PostgreSQL features (SKIP LOCKED, partial indexes, ON CONFLICT upserts, jsonb).
 */
public final class TestDatabase {

    public record Target(String url, String user, String password, String source) {
    }

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/shipflow_test";
    private static Target target;
    private static boolean resolved;
    private static PostgreSQLContainer<?> container;

    private TestDatabase() {
    }

    public static synchronized Target get() {
        if (!resolved) {
            target = resolve();
            resolved = true;
            if (target != null) {
                System.out.println("[TestDatabase] using " + target.source() + " -> " + target.url());
            } else {
                System.out.println("[TestDatabase] no PostgreSQL available; integration tests will be skipped");
            }
        }
        return target;
    }

    private static Target resolve() {
        if (dockerAvailable()) {
            container = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("shipflow_test").withUsername("postgres").withPassword("postgres");
            container.start();
            return new Target(container.getJdbcUrl(), container.getUsername(), container.getPassword(), "testcontainers");
        }
        String url = setting("TEST_DB_URL");
        String user = setting("TEST_DB_USER");
        String password = setting("TEST_DB_PASSWORD");
        if (url != null) {
            Target t = new Target(url, user != null ? user : "postgres", password != null ? password : "postgres", "external (TEST_DB_URL)");
            if (reachable(t)) {
                return t;
            }
            System.out.println("[TestDatabase] TEST_DB_URL set but not reachable: " + url);
            return null;
        }
        Target local = new Target(DEFAULT_URL, "postgres", "postgres", "local default");
        return reachable(local) ? local : null;
    }

    private static boolean dockerAvailable() {
        if ("false".equalsIgnoreCase(setting("TEST_USE_DOCKER"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean reachable(Target t) {
        try {
            DriverManager.setLoginTimeout(3);
            Properties p = new Properties();
            p.setProperty("user", t.user());
            p.setProperty("password", t.password());
            try (Connection c = DriverManager.getConnection(t.url(), p)) {
                return c.isValid(2);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String setting(String name) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        return v == null || v.isBlank() ? null : v;
    }
}
