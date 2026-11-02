package com.sutra.procurement.integration;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Wipes the procurement schema and re-applies /database/procurement before the test context starts. */
@TestConfiguration
public class CleanDatabaseConfig {

    @Bean
    FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
