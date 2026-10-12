package io.github.hemanthvk980.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hemanthvk980.shortener.link.repo.LinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the Flyway migrations and Hibernate schema validation against real PostgreSQL, catching
 * H2-vs-Postgres dialect drift. Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LinkRepository links;

    @Test
    void migrationsApplyAndSchemaValidates() {
        assertThat(links.count()).isZero();
    }
}
