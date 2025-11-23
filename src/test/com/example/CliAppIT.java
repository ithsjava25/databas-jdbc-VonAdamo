package com.example;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * End-to-end CLI tests for the JDBC CRUD console application.
 *
 * Contract expected by these tests (implementation must follow for tests to pass):
 * - The app reads menu choices from standard input and writes results to standard output.
 * - Menu options:
 *   1. List moon missions (read-only from table `moon_mission`) and print spacecraft names.
 *   2. Create an account (table `account`): prompts for first name, last name, ssn, password.
 *   3. Update an account password: prompts for user_id and new password.
 *   4. Delete an account: prompts for user_id to delete.
 *   0. Exit program.
 * - The app should use these system properties for DB access (configured by the tests):
 *   APP_JDBC_URL, APP_DB_USER, APP_DB_PASS
 * - Login flow expected by the tests: prompt for SSN then Password; validate against `account.ssn` + `account.password`.
 * - After each operation the app prints a confirmation message or the read result.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CliAppIT {

    @Container
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:9.5.0")
            .withDatabaseName("testdb")
            .withUsername("user")
            .withPassword("password")
            .withConfigurationOverride("myconfig")
            .withInitScript("init.sql");

    @BeforeAll
    static void wireDbProperties() {
        System.setProperty("APP_JDBC_URL", mysql.getJdbcUrl());
        System.setProperty("APP_DB_USER", mysql.getUsername());
        System.setProperty("APP_DB_PASS", mysql.getPassword());
    }

    @Test
    @Order(0)
    void testConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        assertThat(conn).isNotNull();
    }

    @Test
    @Order(1)
    void login_withInvalidCredentials_showsErrorMessage() throws Exception {
        String input = String.join(System.lineSeparator(),
                // Expect app to prompt for ssn then password
                "000000-0000",      // ssn (invalid)
                "badPassword",       // password (invalid)
                "0"                  // exit immediately after
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .containsIgnoringCase("ssn")
                .containsIgnoringCase("password")
                .containsIgnoringCase("invalid");
    }

    @Test
    @Order(2)
    void login_withValidCredentials_thenCanUseApplication() throws Exception {
        // Using a known seeded account from init.sql:
        // first_name = Angela, last_name = Fransson, ssn = 371108-9221
        // password = MB=V4cbAqPz4vqmQ
        String input = String.join(System.lineSeparator(),
                "371108-9221",          // ssn
                "MB=V4cbAqPz4vqmQ",     // password
                "1",                    // list missions after successful login
                "0"                     // exit
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .containsIgnoringCase("ssn")
                .containsIgnoringCase("password")
                .as("Expected output to contain at least one known spacecraft from seed data after successful login")
                .containsAnyOf("Pioneer 0", "Luna 2", "Luna 3", "Ranger 7");
    }

    @Test
    @Order(3)
    void listMoonMissions_printsKnownMissionNames() throws Exception {
        String input = String.join(System.lineSeparator(),
                // login first
                "371108-9221",
                "MB=V4cbAqPz4vqmQ",
                "1", // list missions
                "0"  // exit
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .as("Expected output to contain at least one known spacecraft from seed data")
                .containsAnyOf("Pioneer 0", "Luna 2", "Luna 3", "Ranger 7");
    }

    @Test
    @Order(4)
    void createAccount_thenCanSeeItInDatabase_andPrintsConfirmation() throws Exception {
        // Count rows before to later verify delta via direct JDBC
        int before = countAccounts();

        String input = String.join(System.lineSeparator(),
                // login first
                "371108-9221",
                "MB=V4cbAqPz4vqmQ",
                "2",            // create account
                "Ada",          // first name
                "Lovelace",     // last name
                "181512-0001",  // ssn
                "s3cr3t",       // password
                "0"             // exit
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .containsIgnoringCase("account")
                .containsIgnoringCase("created");

        int after = countAccounts();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(5)
    void updateAccountPassword_thenRowIsUpdated_andPrintsConfirmation() throws Exception {
        // Prepare: insert a minimal account row directly
        long userId = insertAccount("Test", "User", "111111-1111", "oldpass");

        String input = String.join(System.lineSeparator(),
                // login first
                "371108-9221",
                "MB=V4cbAqPz4vqmQ",
                "3",                 // update password
                Long.toString(userId),// user_id
                "newpass123",        // new password
                "0"                  // exit
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .containsIgnoringCase("updated");

        String stored = readPassword(userId);
        assertThat(stored).isEqualTo("newpass123");
    }

    @Test
    @Order(6)
    void deleteAccount_thenRowIsGone_andPrintsConfirmation() throws Exception {
        long userId = insertAccount("To", "Delete", "222222-2222", "pw");

        String input = String.join(System.lineSeparator(),
                // login first
                "371108-9221",
                "MB=V4cbAqPz4vqmQ",
                "4",                 // delete account
                Long.toString(userId),// user_id
                "0"                  // exit
        ) + System.lineSeparator();

        String out = runMainWithInput(input);

        assertThat(out)
                .containsIgnoringCase("deleted");

        assertThat(existsAccount(userId)).isFalse();
    }

    private static String runMainWithInput(String input) throws Exception {
        // Capture STDOUT
        PrintStream originalOut = System.out;
        InputStream originalIn = System.in;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        System.setOut(capture);
        System.setIn(bais);

        try {
            // Try to find main(String[]) first, fallback to main()
            Class<?> mainClass = Class.forName("com.example.Main");
            Method method = null;
            try {
                method = mainClass.getDeclaredMethod("main", String[].class);
            } catch (NoSuchMethodException ignored) {
                try {
                    method = mainClass.getDeclaredMethod("main");
                } catch (NoSuchMethodException e) {
                    fail("Expected a main entrypoint in com.example.Main. Define either main(String[]) or main().");
                }
            }
            method.setAccessible(true);

            // Invoke with a timeout guard (in case the app blocks)
            final Method finalMethod = method;
            Thread t = new Thread(() -> {
                try {
                    if (finalMethod.getParameterCount() == 1) {
                        finalMethod.invoke(null, (Object) new String[]{});
                    } else {
                        finalMethod.invoke(null);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            t.join(Duration.ofSeconds(10).toMillis());
            if (t.isAlive()) {
                t.interrupt();
                fail("CLI did not exit within timeout. Ensure option '0' exits the program.");
            }

            capture.flush();
            return baos.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(originalOut);
            System.setIn(originalIn);
        }
    }

    private static int countAccounts() throws SQLException {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM account");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long insertAccount(String first, String last, String ssn, String password) throws SQLException {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO account(password, first_name, last_name, ssn) VALUES (?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, password);
            ps.setString(2, first);
            ps.setString(3, last);
            ps.setString(4, ssn);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static String readPassword(long userId) throws SQLException {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT password FROM account WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        }
    }

    private static boolean existsAccount(long userId) throws SQLException {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM account WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
