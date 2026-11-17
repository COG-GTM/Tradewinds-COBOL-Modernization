package com.tradewinds.tradedesk;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.DeserializationFeature;

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

    /** COMM-FACILTYPE is PIC S9(8) COMP: reject 495.99 instead of truncating it to a teller request. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejectFloatsForIntegerFields() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
