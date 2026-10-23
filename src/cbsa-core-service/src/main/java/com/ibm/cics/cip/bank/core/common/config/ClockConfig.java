package com.ibm.cics.cip.bank.core.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Replaces EXEC CICS ASKTIME / FORMATTIME and DB2 CURRENT TIMESTAMP. Every business class must
 * obtain "now" from this {@link Clock} bean, never from {@code LocalDate.now()} without a clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock cbsaClock(CbsaProperties properties) {
        ZoneId zone = ZoneId.of(StringUtils.hasText(properties.clock().zone()) ? properties.clock().zone() : "UTC");
        String fixed = properties.clock().fixed();
        if (!StringUtils.hasText(fixed)) {
            return Clock.system(zone);
        }
        return Clock.fixed(parseInstant(fixed.trim(), zone), zone);
    }

    static Instant parseInstant(String value, ZoneId zone) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        }
    }
}
