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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Периметр сайтов: миграция переводит сайт, проксировавший панель, на статику,
 * а заголовки подписки — на нейтральный {@code Profile-Title}.
 */
class EdgePerimeterMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("018 переводит сайт с портом панели на static и добавляет хост панели")
    void edgePerimeterMigrationTurnsPanelSiteIntoStatic() throws Exception {
        try (Connection connection = open("edge.db")) {
            run(database(connection), "db/changelog/011-edge/edge.sql");
            run(database(connection), "db/changelog/016-edge-static/edge_static.sql");
            insertSites(connection);

            run(database(connection), "db/changelog/018-edge-perimeter/edge_perimeter.sql");

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT site_type, root_path, upstream_host, upstream_port, bind_address, redirect_target "
                                 + "FROM edge_sites WHERE domain = 'pohr-roclh.xyz'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("site_type")).isEqualTo("static");
                assertThat(rs.getString("root_path")).isEqualTo("/app/xray/site");
                assertThat(rs.getString("upstream_host")).isNull();
                assertThat(rs.getObject("upstream_port")).isNull();
                assertThat(rs.getString("bind_address")).isNull();
                assertThat(rs.getString("redirect_target")).isNull();
            }

            assertThat(siteType(connection, "www.pohr-roclh.xyz")).isEqualTo("static");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT site_type, upstream_port FROM edge_sites WHERE domain = 'api.example.com'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("site_type")).isEqualTo("proxy");
                assertThat(rs.getInt("upstream_port")).isEqualTo(9000);
            }

            // Панель не убрана: у неё свой публичный хост для логина и инвайтов.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT site_type, upstream_host, upstream_port, bind_address, tls_mode "
                                 + "FROM edge_sites WHERE domain = 'panel.pohr-roclh.xyz'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("site_type")).isEqualTo("proxy");
                assertThat(rs.getString("upstream_host")).isEqualTo("127.0.0.1");
                assertThat(rs.getInt("upstream_port")).isEqualTo(8080);
                assertThat(rs.getString("bind_address")).isNull();
                assertThat(rs.getString("tls_mode")).isEqualTo("acme");
            }
        }
    }

    @Test
    @DisplayName("019 убирает бренд из сохранённых заголовков клиентов")
    void subNeutralMigrationRenamesProfileTitle() throws Exception {
        try (Connection connection = open("sub.db")) {
            run(database(connection), "db/changelog/007-client-configs/client_configs.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO client_configs (client_name, enabled, user_agent_pattern, headers, updated_at)
                        VALUES ('happ', 1, 'Happ',
                                'profile-title: Pohr' || char(10) || 'profile-update-interval: 6',
                                '2026-11-14T00:00:00Z')
                        """);
            }

            run(database(connection), "db/changelog/019-sub-neutral/sub_neutral.sql");

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT headers FROM client_configs WHERE client_name = 'happ'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("headers"))
                        .contains("profile-title: Subscription")
                        .doesNotContain("Pohr");
            }
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
        Liquibase liquibase = new Liquibase(
                changelog, new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts());
    }

    private static void insertSites(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO edge_sites (id, domain, site_type, upstream_host, upstream_port,
                                            bind_address, redirect_target, tls_mode, enabled, updated_at)
                    VALUES
                      ('00000000-0000-0000-0000-000000000001', 'pohr-roclh.xyz', 'proxy',
                       '127.0.0.1', 8080, '127.0.0.1', NULL, 'acme', 1, '2026-11-14T00:00:00Z'),
                      ('00000000-0000-0000-0000-000000000002', 'www.pohr-roclh.xyz', 'static',
                       NULL, NULL, NULL, NULL, 'acme', 1, '2026-11-14T00:00:00Z'),
                      ('00000000-0000-0000-0000-000000000003', 'api.example.com', 'proxy',
                       '127.0.0.1', 9000, NULL, NULL, 'acme', 1, '2026-11-14T00:00:00Z')
                    """);
        }
    }

    private static String siteType(Connection connection, String domain) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT site_type FROM edge_sites WHERE domain = '" + domain + "'")) {
            return rs.next() ? rs.getString("site_type") : null;
        }
    }
}
