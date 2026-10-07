package io.github.burakboduroglu.ratekit.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/**
 * rating and billing migrate the same database with separate Flyway histories, and nothing makes one
 * start before the other outside compose. This runs each service's real Flyway settings, read from
 * its application.yml, against an empty database in both orders.
 */
@Testcontainers
class MigrationOrderTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    private static final Path RATING = Path.of("../rating/src/main/resources");
    private static final Path BILLING = Path.of("src/main/resources");

    @Test
    void ratingFirstThenBilling() throws Exception {
        String url = emptyDatabase();

        flyway(RATING, url).migrate();
        flyway(BILLING, url).migrate();

        assertThat(tables(url)).contains("accounts", "charges", "top_ups", "invoices", "invoice_lines");
    }

    @Test
    void billingFirstThenRating() throws Exception {
        String url = emptyDatabase();

        flyway(BILLING, url).migrate();
        flyway(RATING, url).migrate();

        assertThat(tables(url)).contains("accounts", "charges", "top_ups", "invoices", "invoice_lines");
        assertThat(flyway(RATING, url).info().pending()).isEmpty();
    }

    /** Flyway configured the way Spring Boot configures it from the service's application.yml. */
    @SuppressWarnings("unchecked")
    private static Flyway flyway(Path resources, String url) throws IOException {
        Map<String, Object> settings;
        try (InputStream in = Files.newInputStream(resources.resolve("application.yml"))) {
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) yaml.getOrDefault("spring", Map.of());
            settings = (Map<String, Object>) spring.getOrDefault("flyway", Map.of());
        }
        String location = String.valueOf(settings.getOrDefault("locations", "classpath:db/migration"));
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + resources.resolve(location.replace("classpath:", "")))
                .table(String.valueOf(settings.getOrDefault("table", "flyway_schema_history")))
                .baselineOnMigrate(Boolean.parseBoolean(String.valueOf(settings.getOrDefault("baseline-on-migrate", false))))
                .baselineVersion(String.valueOf(settings.getOrDefault("baseline-version", "1")))
                .load();
    }

    private static String emptyDatabase() throws SQLException {
        String name = "order_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static List<String> tables(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        }
    }
}
