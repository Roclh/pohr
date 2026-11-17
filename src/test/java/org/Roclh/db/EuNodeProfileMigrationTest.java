package org.Roclh.db;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет миграцию Т03 на пустой и на существующей БД: аддитивные ALTER'ы
 * должны применяться поверх таблицы с данными и не терять строки.
 */
class EuNodeProfileMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("полный changelog создаёт колонки профиля и таблицу истории")
    void fullChangelogAddsProfileColumns() throws Exception {
        try (Connection connection = open("fresh.db")) {
            Database database = database(connection);
            run(database, "db/changelog/db.changelog-master.yaml");

            List<String> columns = columns(connection, "eu_nodes");
            assertThat(columns).contains("sni", "target", "fingerprint", "profile_updated_at");
            assertThat(tableExists(connection, "eu_node_profile_history")).isTrue();
        }
    }

    @Test
    @DisplayName("017 применяется к существующей таблице с данными и не теряет строки")
    void migrationAppliesToExistingData() throws Exception {
        try (Connection connection = open("existing.db")) {
            run(database(connection), "db/changelog/005-eu-nodes/eu_nodes.sql");
            insertLegacyNode(connection);

            run(database(connection), "db/changelog/017-eu-node-profile/eu_node_profile.sql");

            List<String> columns = columns(connection, "eu_nodes");
            assertThat(columns).contains("sni", "target", "fingerprint", "profile_updated_at");

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT name, sni, target FROM eu_nodes")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("eu-poland-1");
                assertThat(rs.getString("sni")).isNull();
                assertThat(rs.getString("target")).isNull();
            }
            assertThat(tableExists(connection, "eu_node_profile_history")).isTrue();
        }
    }

    private Connection open(String fileName) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath());
        connection.setAutoCommit(true);
        return connection;
    }

    private static Database database(Connection connection) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        database.setDatabaseChangeLogTableName("DATABASECHANGELOG");
        database.setDatabaseChangeLogLockTableName("DATABASECHANGELOGLOCK");
        return database;
    }

    private static void run(Database database, String changelog) throws Exception {
        // Liquibase#close() закрывает переданное соединение — не оборачиваем
        // в try-with-resources, соединение нужно тесту после миграции.
        Liquibase liquibase = new Liquibase(
                changelog, new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts());
    }

    private static List<String> columns(Connection connection, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void insertLegacyNode(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO eu_nodes (id, name, host, port, reality_public_key, reality_short_id,
                                      tunnel_uuid, node_secret, status, enrolled_at, enrolled_by)
                VALUES ('00000000-0000-0000-0000-000000000001', 'eu-poland-1', '78.17.145.160', 8443,
                        'public-key', 'aabbccdd', '00000000-0000-0000-0000-000000000002', 'secret',
                        'HEALTHY', '2026-11-14T00:00:00Z', '00000000-0000-0000-0000-000000000003')
                """)) {
            statement.executeUpdate();
        }
    }
}
