package com.ibm.cics.cip.bank.core.bankdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The dev-only endpoint is only registered when cbsa.bankdata.endpoint.enabled=true. */
class BankDataEndpointDisabledTest {

    @Configuration
    @Import(BankDataController.class)
    static class Config {
        @Bean
        BankDataGenerator generator() {
            return new BankDataGenerator("987654", BankDataGeneratorTest.FIXED, 0L);
        }

        @Bean
        BankDataLoader loader() {
            return new BankDataLoader(null, null, null);
        }

        @Bean
        BankDataSqlWriter writer() {
            return new BankDataSqlWriter();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of()).withUserConfiguration(Config.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(BankDataController.class));
        runner.withPropertyValues("cbsa.bankdata.endpoint.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BankDataController.class));
    }

    @Test
    void presentWhenEnabled() {
        runner.withPropertyValues("cbsa.bankdata.endpoint.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(BankDataController.class));
    }
}
