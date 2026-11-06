package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The test database: one PostgreSQL 18 container for the whole test run, with the two roles of CONVENTIONS §7 created
 * by {@code db/test-roles.sql}; or, where Docker is not available, an external PostgreSQL named by
 * {@code TINFIRM_TEST_JDBC_URL} (with {@code TINFIRM_TEST_SUPERUSER} and {@code TINFIRM_TEST_SUPERUSER_PASSWORD}),
 * on which the two roles already exist with the passwords below, and the database the URL names, owned by
 * {@code tinfirm_owner} (any name: two runs on one server each name their own). A test that needs a fresh instance
 * creates a database named after the test database's ({@link #createDatabase}), so two runs do not share it either.
 * The application connects as {@code tinfirm_app}, so row-level security is really exercised; Flyway runs as
 * {@code tinfirm_owner}. All passwords here are test fixtures.
 */
public final class TestDatabase {

    public static final String DATABASE = "tinfirm";

    public static final String OWNER = "tinfirm_owner";

    public static final String OWNER_PASSWORD = "owner-test-password";

    public static final String APP = "tinfirm_app";

    public static final String APP_PASSWORD = "app-test-password";

    private static final String EXTERNAL_URL = System.getenv("TINFIRM_TEST_JDBC_URL");

    /** A PostgreSQL JDBC URL: what comes before the database's name, the name, and the parameters after it. */
    private static final Pattern URL = Pattern.compile("^(jdbc:postgresql://[^/]*/)([^?;]+)(.*)$");

    /** The number of the JVM among Surefire's parallel ones (server/pom.xml), from 1. */
    private static final String FORK = "tinfirm.test.fork";

    private static final String SUPERUSER;

    private static final String SUPERUSER_PASSWORD;

    private static final String JDBC_URL;

    static {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            SUPERUSER = System.getenv().getOrDefault("TINFIRM_TEST_SUPERUSER", "postgres");
            SUPERUSER_PASSWORD = System.getenv().getOrDefault("TINFIRM_TEST_SUPERUSER_PASSWORD", "postgres");
            // Surefire runs the test classes in parallel JVMs (server/pom.xml): each gets a database of its own on the
            // server, named after the given one, as the container gives each its own server.
            String fork = System.getProperty(FORK);
            JDBC_URL = fork == null || fork.isBlank() ? EXTERNAL_URL
                    : recreate(EXTERNAL_URL, urlOf(EXTERNAL_URL).group(2) + "_fork" + fork, SUPERUSER,
                            SUPERUSER_PASSWORD);
        }
        else {
            PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
                .withDatabaseName(DATABASE)
                .withInitScript("db/test-roles.sql");
            postgres.start();
            JDBC_URL = postgres.getJdbcUrl();
            SUPERUSER = postgres.getUsername();
            SUPERUSER_PASSWORD = postgres.getPassword();
        }
    }

    private TestDatabase() {
    }

    public static String jdbcUrl() {
        return JDBC_URL;
    }

    /** The name of the test database: {@code tinfirm} in the container, else the one the external URL names. */
    public static String databaseName() {
        return url().group(2);
    }

    /** JDBC URL of another database on the same server, with the test database's parameters. */
    public static String jdbcUrl(String database) {
        Matcher url = url();
        return url.group(1) + database + url.group(3);
    }

    private static Matcher url() {
        return urlOf(JDBC_URL);
    }

    private static Matcher urlOf(String jdbcUrl) {
        Matcher url = URL.matcher(jdbcUrl);
        if (!url.matches()) {
            throw new IllegalStateException("not a PostgreSQL JDBC URL with a database: " + jdbcUrl);
        }
        return url;
    }

    /**
     * Creates an empty database owned by {@code tinfirm_owner}, for tests that need a fresh instance, named after the
     * test database with the suffix ({@code tinfirm_last_admin} beside {@code tinfirm}), so that two runs on one
     * server never drop each other's.
     *
     * @return its JDBC URL
     */
    public static String createDatabase(String suffix) {
        return recreate(JDBC_URL, databaseName() + "_" + suffix, SUPERUSER, SUPERUSER_PASSWORD);
    }

    /** Drops and creates the database, owned by {@code tinfirm_owner}, on the server of the URL; answers its URL. */
    private static String recreate(String serverUrl, String database, String superuser, String password) {
        // A database name is an identifier and cannot be a bind parameter, so only plain names pass.
        if (!database.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("not a plain database name: " + database);
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, superuser, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            statement.execute("CREATE DATABASE " + database + " OWNER " + OWNER); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        Matcher url = urlOf(serverUrl);
        return url.group(1) + database + url.group(3);
    }

    /** A connection as the container's superuser to a database of the test server; row-level security is bypassed. */
    public static Connection connectAsSuperuser(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, SUPERUSER, SUPERUSER_PASSWORD);
    }

    /** A connection as the runtime role; row-level security applies. */
    public static Connection connectAsApp() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP, APP_PASSWORD);
    }

    /** A connection as the schema owner (used to simulate tampering by someone with database access). */
    public static Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), OWNER, OWNER_PASSWORD);
    }
}
