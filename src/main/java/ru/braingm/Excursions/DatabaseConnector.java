package ru.braingm.Excursions;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.braingm.ConfigLoader;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseConnector {
    private static volatile DatabaseConnector instance;
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnector.class);
    @Getter
    private Connection connection;
    private final String excursionsDbUrl;

    private DatabaseConnector() {
        excursionsDbUrl = new ConfigLoader().getProperty("database.ExcursionsUrl");
        try {
            connection = DriverManager.getConnection(excursionsDbUrl);

            //Инициализация базы, если её нет
            String createExcursionTable = """
                        CREATE TABLE IF NOT EXISTS excursions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT NOT NULL,
                            description TEXT NOT NULL
                        )
                    """;
            connection.createStatement().execute(createExcursionTable);

            String createDataTable = """
                        CREATE TABLE IF NOT EXISTS data (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT NOT NULL,
                            description TEXT NOT NULL
                        )
                    """;

            connection.createStatement().execute(createDataTable);

        } catch (SQLException e) {
            logger.error("Error initializing database connection: {}", e.getMessage());
            throw new RuntimeException("Database connection failed", e);
        }
    }

    public synchronized List<Excursion> loadExcursionsFromDatabase() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, title, description FROM excursions")) {
            List<Excursion> newExcursions = new ArrayList<>();
            while (rs.next()) {
                newExcursions.add(new Excursion(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("description")
                ));
            }
            logger.info("Loaded {} excursions", newExcursions.size());
            return newExcursions;
        } catch (SQLException e) {
            logger.error("Failed to load excursions", e);
        }
        return new ArrayList<>();
    }

    public void writeExcursion(String title, String description) {
        try {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO excursions (title, description) VALUES (?, ?)");
            statement.setString(1, title);
            statement.setString(2, description);
            statement.executeUpdate();
            logger.info("Excursion wrote");
        } catch (SQLException e) {
            logger.error("Error writing excursion");
            throw new RuntimeException(e);
        }
    }


    public static DatabaseConnector getInstance() {
        if (instance == null) {
            synchronized (DatabaseConnector.class) {
                if (instance == null) {
                    instance = new DatabaseConnector();
                }
            }
        }
        return instance;
    }

    public Excursion getExcursion(int id) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT title, description FROM excursions WHERE id = ?")) {
            statement.setInt(1, id);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return new Excursion(
                        id,
                        rs.getString(1),
                        rs.getString(2)
                );
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public String loadData(int id) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT (description) FROM data WHERE ID = ?")) {
            statement.setInt(1, id);
            ResultSet set = statement.executeQuery();
            if (set.next())
                return set.getString(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return "пусто";
    }

    public void writeData(int id, String text) {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE data SET description = ? WHERE id = ?")) {
            statement.setString(1, text);
            statement.setInt(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}

