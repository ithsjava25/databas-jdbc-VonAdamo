package com.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.Arrays;
import java.util.Scanner;

public class Main {

    static void main(String[] args) {
        if (isDevMode(args)) {
            DevDatabaseInitializer.start();
        }
        new Main().run();
    }

    public void run() {
        // Resolve DB settings with precedence: System properties -> Environment variables
        String jdbcUrl = resolveConfig("APP_JDBC_URL", "APP_JDBC_URL");
        String dbUser = resolveConfig("APP_DB_USER", "APP_DB_USER");
        String dbPass = resolveConfig("APP_DB_PASS", "APP_DB_PASS");

        if (jdbcUrl == null || dbUser == null || dbPass == null) {
            throw new IllegalStateException(
                    "Missing DB configuration. Provide APP_JDBC_URL, APP_DB_USER, APP_DB_PASS " +
                            "as system properties (-Dkey=value) or environment variables.");
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPass)) {
            connection.setAutoCommit(true);

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

            boolean loggedIn = loginLoop(connection, in);
            if (!loggedIn) {
                return;
            }
            menuLoop(connection, in);

        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean loginLoop (Connection connection, BufferedReader in) throws IOException {
        while (true) {
            System.out.println("Username: ");
            String username = readTrimmed(in);

            System.out.println("Password: ");
            String password = readTrimmed(in);

            if (isValidLogin(connection, username, password)) {
                return true;
            }
            System.out.println("Invalid username or password");
            System.out.println("1) Try again");
            System.out.println("0) Exit...");
            String choice = readTrimmed(in);
            if ("0".equals(choice)) return false;
        }
    }

    private void menuLoop (Connection connection, BufferedReader in) throws IOException {
        while (true) {
            printMenu("User");
            System.out.println("Select an option:");
            String choice = readTrimmed(in);

            switch (choice) {
                case "1" -> listMoonMissions(connection);
                case "2" -> getMoonMissionsById(connection, in);
                case "3" -> countMissionByYear(connection, in);
                case "4" -> createAccount(connection, in);
                case "5" -> updateAccount(connection, in);
                case "6" -> deleteAccount(connection, in);
                case "0" -> { return; }
                default -> System.out.println("invalid option");
            }
        }
    }

    private void printMenu(String name) {
        System.out.println("Welcome to the Moon Mission Database, " + name + "!");
        System.out.println("1) List moon missions."); //(prints spacecraft names from `moon_mission`");
        System.out.println("2) Get a moon mission by mission_id."); //(prints details for that mission).");
        System.out.println("3) Count missions for a given year."); //(prompts: year; prints the number of missions launched that year).");
        System.out.println("4) Create an account."); //(prompts: first name, last name, ssn, password; prints confirmation).");
        System.out.println("5) Update an account password."); //(prompts: user_id, new password; prints confirmation).");
        System.out.println("6) Delete an account."); //(prompts: user_id; prints confirmation).");
        System.out.println("0) Exit.");
    }

    private String readTrimmed(BufferedReader in) throws IOException {
        String s = in.readLine();
        return (s == null) ? "" : s.trim();
    }

    private boolean isValidLogin (Connection connection, String username, String password) {
        String sql = "SELECT 1 FROM account WHERE name = ? AND password = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, password);

            try (ResultSet res = stmt.executeQuery()) {
                return res.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void listMoonMissions (Connection connection) {
        String sql = "SELECT spacecraft FROM moon_mission ORDER BY mission_id";

        try (PreparedStatement stmt = connection.prepareStatement(sql);
            ResultSet res = stmt.executeQuery()) {

            while (res.next()) {
                System.out.println(res.getString("spacecraft"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void getMoonMissionsById (Connection connection, BufferedReader in) throws IOException {
        System.out.println("mission_id: ");
        int id = Integer.parseInt(readTrimmed(in));

        String sql = """
            SELECT mission_id, spacecraft, launch_date, carrier_rocket, operator, mission_type, outcome
            FROM moon_mission
            WHERE mission_id = ?
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);

            try (ResultSet res = stmt.executeQuery()) {
                if (!res.next()) {
                    System.out.println("Not found");
                    return;
                }
                System.out.println("Mission ID: " + res.getInt("mission_id"));
                System.out.println("Spacecraft: " + res.getString("spacecraft"));
                System.out.println("Launch Date: " + res.getDate("launch_date"));
                System.out.println("Carrier Rocket: " + res.getString("carrier_rocket"));
                System.out.println("Operator: " + res.getString("operator"));
                System.out.println("Mission Type: " + res.getString("mission_type"));
                System.out.println("Outcome: " + res.getString("outcome"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void countMissionByYear (Connection connection, BufferedReader in) throws IOException {
        System.out.println("year");
        int year = Integer.parseInt(readTrimmed(in));

        String sql ="SELECT COUNT(*) AS count FROM moon_mission WHERE YEAR(launch_date) = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, year);
            try (ResultSet res = stmt.executeQuery()) {
                res.next();
                System.out.println(year + ": " + res.getInt("count"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void createAccount (Connection connection, BufferedReader in) throws IOException {
        System.out.println("First name: ");
        String firstName = readTrimmed(in);

        System.out.println("Last name: ");
        String lastName = readTrimmed(in);

        System.out.println("SSN: ");
        String ssn = readTrimmed(in);

        System.out.println("Password: ");
        String password = readTrimmed(in);

        String name = makeName(firstName,lastName);

        String sql = "INSERT INTO account (name, first_name, last_name, ssn, password) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, firstName);
            stmt.setString(3, lastName);
            stmt.setString(4, ssn);
            stmt.setString(5, password);

            stmt.executeUpdate();
            System.out.println("Account created for " + name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void updateAccount (Connection connection, BufferedReader in) throws IOException {
        System.out.println("User ID: ");
        int userId = Integer.parseInt(readTrimmed(in));

        System.out.println("New Password: ");
        String newPass = readTrimmed(in);

        String sql = "UPDATE account SET password = ? WHERE user_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newPass);
            stmt.setInt(2, userId);

            int updated = stmt.executeUpdate();
            if (updated == 0) {
                System.out.println("No account found with user ID " + userId);
            } else {
                System.out.println("Password updated for user ID " + userId);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteAccount (Connection connection, BufferedReader in) throws IOException {
        System.out.println("User ID: ");
        int userId = Integer.parseInt(readTrimmed(in));

        String sql = "DELETE FROM account WHERE user_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, userId);

            int deleted = stmt.executeUpdate();
            if (deleted == 0) {
                System.out.println("No account found with user ID " + userId);
            } else {
                System.out.println("Account deleted for user ID " + userId);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String makeName (String firstName, String lastName) {
        String f = (firstName == null) ? "" : firstName.trim();
        String l = (lastName == null) ? "" : lastName.trim();
        String f3 = f.length() >= 3 ? f.substring(0, 3) : f;
        String l3 = l.length() >= 3 ? l.substring(0, 3) : l;
        return (f3 + l3);
    }

    /**
     * Determines if the application is running in development mode based on system properties,
     * environment variables, or command-line arguments.
     *
     * @param args an array of command-line arguments
     * @return {@code true} if the application is in development mode; {@code false} otherwise
     */
    private static boolean isDevMode(String[] args) {
        if (Boolean.getBoolean("devMode"))  //Add VM option -DdevMode=true
            return true;
        if ("true".equalsIgnoreCase(System.getenv("DEV_MODE")))  //Environment variable DEV_MODE=true
            return true;
        return Arrays.asList(args).contains("--dev"); //Argument --dev
    }

    /**
     * Reads configuration with precedence: Java system property first, then environment variable.
     * Returns trimmed value or null if neither source provides a non-empty value.
     */
    private static String resolveConfig(String propertyKey, String envKey) {
        String v = System.getProperty(propertyKey);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(envKey);
        }
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
