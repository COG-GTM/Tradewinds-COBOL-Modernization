package com.tradewinds.tradedesk;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TradeDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeDeskApplication.class, args);
    }

    /** Source of EXEC CICS ASKTIME; replaceable in tests. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
